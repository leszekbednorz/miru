package com.jivesoftware.os.miru.service.partition;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.MiruBackingStorage;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionCoordInfo;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.MiruStats;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.api.activity.TimeAndVersion;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchemaUnvailableException;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.topology.MiruPartitionActive;
import com.jivesoftware.os.miru.api.wal.MiruActivityWALStatus;
import com.jivesoftware.os.miru.api.wal.MiruCursor;
import com.jivesoftware.os.miru.api.wal.MiruSipCursor;
import com.jivesoftware.os.miru.api.wal.MiruWALClient;
import com.jivesoftware.os.miru.api.wal.MiruWALEntry;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.partition.MiruHostedPartition;
import com.jivesoftware.os.miru.plugin.partition.MiruPartitionUnavailableException;
import com.jivesoftware.os.miru.plugin.partition.MiruQueryablePartition;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestHandle;
import com.jivesoftware.os.miru.service.NamedThreadFactory;
import com.jivesoftware.os.miru.service.stream.MiruContext;
import com.jivesoftware.os.miru.service.stream.MiruContextFactory;
import com.jivesoftware.os.miru.service.stream.MiruIndexer;
import com.jivesoftware.os.miru.service.stream.MiruRebuildDirector;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.health.api.HealthCounter;
import com.jivesoftware.os.routing.bird.health.api.HealthFactory;
import com.jivesoftware.os.routing.bird.health.api.MinMaxHealthCheckConfig;
import com.jivesoftware.os.routing.bird.health.api.MinMaxHealthChecker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.merlin.config.defaults.LongDefault;
import org.merlin.config.defaults.StringDefault;

/**
 * @author jonathan
 */
public class MiruLocalHostedPartition<BM extends IBM, IBM, C extends MiruCursor<C, S>, S extends MiruSipCursor<S>>
    implements MiruHostedPartition, MiruQueryablePartition<BM, IBM> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final MiruStats miruStats;
    private final MiruBitmaps<BM, IBM> bitmaps;
    private final TrackError trackError;
    private final MiruPartitionCoord coord;
    private final AtomicLong expireAfterTimestamp;
    private final MiruContextFactory<S> contextFactory;
    private final MiruSipTrackerFactory<S> sipTrackerFactory;
    private final MiruWALClient<C, S> walClient;
    private final MiruPartitionHeartbeatHandler heartbeatHandler;
    private final MiruRebuildDirector rebuildDirector;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    private final Collection<ScheduledFuture<?>> futures;
    private final ScheduledExecutorService scheduledRebuildExecutor;
    private final ScheduledExecutorService scheduledSipExecutor;
    private final ExecutorService rebuildWALExecutors;
    private final ExecutorService sipIndexExecutor;
    private final ExecutorService mergeExecutor;
    private final int rebuildIndexerThreads;
    private final MiruIndexRepairs indexRepairs;
    private final MiruIndexer<BM, IBM> indexer;
    private final long partitionRebuildIfBehindByCount;
    private final int partitionRebuildBatchSize;
    private final int partitionSipBatchSize;
    private final MiruMergeChits mergeChits;
    private final Timings timings;

    private final AtomicReference<MiruPartitionAccessor<BM, IBM, C, S>> accessorRef = new AtomicReference<>();
    private final Object factoryLock = new Object();
    private final AtomicBoolean firstRebuild = new AtomicBoolean(true);
    private final AtomicBoolean firstSip = new AtomicBoolean(true);

    private interface BootstrapCount extends MinMaxHealthCheckConfig {

        @StringDefault("rebuild>pending")
        @Override
        String getName();

        @StringDefault("Number of partitions that need to be rebuilt before service is considered fully online.")
        @Override
        String getDescription();

        @LongDefault(0)
        @Override
        Long getMin();

        @LongDefault(1_000)
        @Override
        Long getMax();
    }

    private static final HealthCounter bootstrapCounter = HealthFactory.getHealthCounter(BootstrapCount.class, MinMaxHealthChecker.FACTORY);

    public MiruLocalHostedPartition(
        MiruStats miruStats,
        MiruBitmaps<BM, IBM> bitmaps,
        TrackError trackError,
        MiruPartitionCoord coord,
        long expireAfterTimestamp,
        MiruContextFactory<S> contextFactory,
        MiruSipTrackerFactory<S> sipTrackerFactory,
        MiruWALClient<C, S> walClient,
        MiruPartitionHeartbeatHandler heartbeatHandler,
        MiruRebuildDirector rebuildDirector,
        ScheduledExecutorService scheduledBootstrapExecutor,
        ScheduledExecutorService scheduledRebuildExecutor,
        ScheduledExecutorService scheduledSipExecutor,
        ExecutorService rebuildWALExecutors,
        ExecutorService sipIndexExecutor,
        ExecutorService mergeExecutor,
        int rebuildIndexerThreads,
        MiruIndexRepairs indexRepairs,
        MiruIndexer<BM, IBM> indexer,
        long partitionRebuildIfBehindByCount,
        int partitionRebuildBatchSize,
        int partitionSipBatchSize,
        MiruMergeChits mergeChits,
        Timings timings)
        throws Exception {

        this.miruStats = miruStats;
        this.bitmaps = bitmaps;
        this.trackError = trackError;
        this.coord = coord;
        this.expireAfterTimestamp = new AtomicLong(expireAfterTimestamp);
        this.contextFactory = contextFactory;
        this.sipTrackerFactory = sipTrackerFactory;
        this.walClient = walClient;
        this.heartbeatHandler = heartbeatHandler;
        this.rebuildDirector = rebuildDirector;
        this.scheduledRebuildExecutor = scheduledRebuildExecutor;
        this.scheduledSipExecutor = scheduledSipExecutor;
        this.rebuildWALExecutors = rebuildWALExecutors;
        this.sipIndexExecutor = sipIndexExecutor;
        this.mergeExecutor = mergeExecutor;
        this.rebuildIndexerThreads = rebuildIndexerThreads;
        this.indexRepairs = indexRepairs;
        this.indexer = indexer;
        this.partitionRebuildIfBehindByCount = partitionRebuildIfBehindByCount;
        this.partitionRebuildBatchSize = partitionRebuildBatchSize;
        this.partitionSipBatchSize = partitionSipBatchSize;
        this.mergeChits = mergeChits;
        this.timings = timings;
        this.futures = Lists.newCopyOnWriteArrayList(); // rebuild, sip-migrate

        MiruPartitionCoordInfo coordInfo = new MiruPartitionCoordInfo(MiruPartitionState.offline, contextFactory.findBackingStorage(coord));
        MiruPartitionAccessor<BM, IBM, C, S> accessor = MiruPartitionAccessor.initialize(miruStats,
            bitmaps,
            coord,
            coordInfo,
            Optional.<MiruContext<IBM, S>>absent(),
            indexRepairs,
            indexer);
        heartbeatHandler.heartbeat(coord, Optional.of(coordInfo), Optional.<Long>absent());
        this.accessorRef.set(accessor);
        log.incAtomic("state>" + accessor.info.state.name());
        log.incAtomic("storage>" + accessor.info.storage.name());

        scheduledBootstrapExecutor.scheduleWithFixedDelay(
            new BootstrapRunnable(), 0, timings.partitionBootstrapIntervalInMillis, TimeUnit.MILLISECONDS);

    }

    private MiruPartitionAccessor<BM, IBM, C, S> open(MiruPartitionAccessor<BM, IBM, C, S> accessor,
        MiruPartitionCoordInfo coordInfo,
        StackBuffer stackBuffer) throws Exception {

        synchronized (factoryLock) {
            MiruPartitionState openingState = coordInfo.state;

            Optional<MiruContext<IBM, S>> optionalContext = Optional.absent();
            if (openingState != MiruPartitionState.offline && openingState != MiruPartitionState.bootstrap) {
                if (accessor.context.isPresent()) {
                    optionalContext = accessor.context;
                } else {
                    MiruContext<IBM, S> context = contextFactory.allocate(bitmaps, coord, accessor.info.storage, stackBuffer);
                    optionalContext = Optional.of(context);
                }
            }
            MiruPartitionAccessor<BM, IBM, C, S> opened = MiruPartitionAccessor.initialize(miruStats,
                bitmaps,
                coord,
                coordInfo.copyToState(openingState),
                optionalContext,
                indexRepairs,
                indexer);
            opened = updatePartition(accessor, opened);
            if (opened != null) {
                if (accessor.info.state == MiruPartitionState.offline && openingState != MiruPartitionState.offline) {
                    accessor.refundChits(mergeChits);
                    clearFutures();
                    futures.add(scheduledRebuildExecutor.scheduleWithFixedDelay(new RebuildIndexRunnable(),
                        0, timings.partitionRebuildIntervalInMillis, TimeUnit.MILLISECONDS));
                    futures.add(scheduledSipExecutor.scheduleWithFixedDelay(new SipMigrateIndexRunnable(),
                        0, timings.partitionSipMigrateIntervalInMillis, TimeUnit.MILLISECONDS));
                }
                return opened;
            } else {
                return accessor;
            }
        }
    }

    @Override
    public MiruRequestHandle<BM, IBM, S> acquireQueryHandle(StackBuffer stackBuffer) throws Exception {
        heartbeatHandler.heartbeat(coord, Optional.<MiruPartitionCoordInfo>absent(), Optional.of(System.currentTimeMillis()));

        if (removed.get()) {
            throw new MiruPartitionUnavailableException("Partition has been removed");
        }

        MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
        if (accessor.canHotDeploy()) {
            log.info("Hot deploying for query: {}", coord);
            accessor = open(accessor, accessor.info.copyToState(MiruPartitionState.online), stackBuffer);
        }
        return accessor.getRequestHandle();
    }

    @Override
    public MiruRequestHandle<BM, IBM, S> inspectRequestHandle() throws Exception {
        if (removed.get()) {
            throw new MiruPartitionUnavailableException("Partition has been removed");
        }

        MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
        return accessor.getRequestHandle();
    }

    @Override
    public void remove() throws Exception {
        log.info("Removing partition by request: {}", coord);
        removed.set(true);
        close();
    }

    private boolean close() throws Exception {
        try {
            synchronized (factoryLock) {
                MiruPartitionAccessor<BM, IBM, C, S> existing = accessorRef.get();
                MiruPartitionCoordInfo coordInfo = existing.info.copyToState(MiruPartitionState.offline);
                MiruPartitionAccessor<BM, IBM, C, S> closed = MiruPartitionAccessor.initialize(miruStats, bitmaps, coord, coordInfo,
                    Optional.<MiruContext<IBM, S>>absent(),
                    indexRepairs,
                    indexer);
                closed = updatePartition(existing, closed);
                if (closed != null) {
                    if (existing.context != null) {
                        Optional<MiruContext<IBM, S>> closedContext = existing.close();
                        if (closedContext.isPresent()) {
                            contextFactory.close(closedContext.get(), existing.info.storage);
                        }
                    }
                    existing.refundChits(mergeChits);
                    clearFutures();
                    return true;
                } else {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to close partition", e);
        }
    }

    private void clearFutures() {
        for (ScheduledFuture<?> future : futures) {
            future.cancel(true);
        }
        futures.clear();
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public MiruPartitionCoord getCoord() {
        return coord;
    }

    public boolean isExpired() {
        long expire = expireAfterTimestamp.get();
        return expire > 0 && System.currentTimeMillis() > expire;
    }

    public void cancelExpiration() {
        expireAfterTimestamp.set(-1);
    }

    @Override
    public MiruPartitionId getPartitionId() {
        return coord.partitionId;
    }

    @Override
    public MiruPartitionState getState() {
        return accessorRef.get().info.state;
    }

    @Override
    public MiruBackingStorage getStorage() {
        return accessorRef.get().info.storage;
    }

    @Override
    public MiruTenantId getTenantId() {
        return coord.tenantId;
    }

    @Override
    public void index(Iterator<MiruPartitionedActivity> partitionedActivities) throws Exception {
        // intentionally locking all stream writes for the entire batch to avoid getting a lock for each activity
        MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
        if (accessor.isOpenForWrites()) {
            ExecutorService sameThreadExecutor = MoreExecutors.sameThreadExecutor();
            StackBuffer stackBuffer = new StackBuffer();
            int count = accessor.indexInternal(partitionedActivities,
                MiruPartitionAccessor.IndexStrategy.ingress,
                false,
                mergeChits,
                sameThreadExecutor,
                sameThreadExecutor,
                trackError,
                stackBuffer);
            if (count > 0) {
                log.inc("indexIngress>written", count);
            }
        } else {
            int count = 0;
            while (partitionedActivities.hasNext()) {
                MiruPartitionedActivity partitionedActivity = partitionedActivities.next();
                if (partitionedActivity.partitionId.equals(coord.partitionId)) {
                    partitionedActivities.remove();
                    count++;
                }
            }
            log.inc("indexIngress>dropped", count);
        }
    }

    @Override
    public void warm() throws Exception {
        //TODO this should tell us to sip
        log.inc("warm", 1);
        log.inc("warm", 1, coord.tenantId.toString());
        log.inc("warm>partition>" + coord.partitionId, 1, coord.tenantId.toString());
    }

    @Override
    public boolean setStorage(MiruBackingStorage storage, StackBuffer stackBuffer) throws Exception {
        return updateStorage(accessorRef.get(), storage, true, stackBuffer);
    }

    private boolean updateStorage(MiruPartitionAccessor<BM, IBM, C, S> accessor,
        MiruBackingStorage destinationStorage,
        boolean force,
        StackBuffer stackBuffer) throws Exception {
        synchronized (factoryLock) {
            boolean updated = false;
            try (MiruMigrationHandle<BM, IBM, C, S> handle = accessor.getMigrationHandle(timings.partitionMigrationWaitInMillis)) {
                // make sure the accessor didn't change while getting the handle, and that it's ready to migrate
                if (accessorRef.get() == accessor && handle.canMigrateTo(destinationStorage)) {
                    Optional<MiruContext<IBM, S>> optionalFromContext = handle.getContext();
                    MiruBackingStorage existingStorage = accessor.info.storage;
                    if (existingStorage == destinationStorage && !force) {
                        log.warn("Partition at {} ignored request to migrate to same storage {}", coord, destinationStorage);
                    } else if (existingStorage == MiruBackingStorage.memory && destinationStorage == MiruBackingStorage.disk) {
                        if (!optionalFromContext.isPresent()) {
                            log.warn("Partition at {} ignored request to migrate from {} to {} for empty context", coord, existingStorage, destinationStorage);
                        }
                        MiruContext<IBM, S> fromContext = optionalFromContext.get();
                        contextFactory.cleanDisk(coord);

                        MiruContext<IBM, S> toContext;
                        synchronized (fromContext.writeLock) {
                            handle.merge(mergeChits, mergeExecutor, trackError, stackBuffer);
                            toContext = contextFactory.copy(bitmaps, coord, fromContext, destinationStorage, stackBuffer);
                        }

                        MiruPartitionAccessor<BM, IBM, C, S> migrated = handle.migrated(toContext, Optional.of(destinationStorage),
                            Optional.<MiruPartitionState>absent());

                        migrated = updatePartition(accessor, migrated);
                        if (migrated != null) {
                            contextFactory.close(fromContext, existingStorage);
                            contextFactory.markStorage(coord, destinationStorage);
                            updated = true;
                        } else {
                            log.warn("Partition at {} failed to migrate to {}, attempting to rewind", coord, destinationStorage);
                            contextFactory.close(toContext, destinationStorage);
                            contextFactory.cleanDisk(coord);
                            contextFactory.markStorage(coord, existingStorage);
                        }
                    } else if (existingStorage == MiruBackingStorage.disk && destinationStorage == MiruBackingStorage.memory) {
                        MiruContext<IBM, S> toContext = contextFactory.allocate(bitmaps, coord, destinationStorage, stackBuffer);
                        MiruPartitionAccessor<BM, IBM, C, S> migrated = handle.migrated(toContext, Optional.of(destinationStorage),
                            Optional.of(MiruPartitionState.bootstrap));

                        migrated = updatePartition(accessor, migrated);
                        if (migrated != null) {
                            Optional<MiruContext<IBM, S>> closed = handle.closeContext();
                            if (closed.isPresent()) {
                                contextFactory.close(closed.get(), existingStorage);
                            }
                            contextFactory.cleanDisk(coord);
                            contextFactory.markStorage(coord, destinationStorage);
                            updated = true;
                        } else {
                            log.warn("Partition at {} failed to migrate to {}, attempting to rewind", coord, destinationStorage);
                            contextFactory.close(toContext, destinationStorage);
                            contextFactory.markStorage(coord, existingStorage);
                        }
                    } else if (existingStorage == MiruBackingStorage.memory && destinationStorage == MiruBackingStorage.memory) {
                        MiruContext<IBM, S> toContext = contextFactory.allocate(bitmaps, coord, destinationStorage, stackBuffer);
                        MiruPartitionAccessor<BM, IBM, C, S> migrated = handle.migrated(toContext, Optional.of(destinationStorage),
                            Optional.of(MiruPartitionState.bootstrap));

                        migrated = updatePartition(accessor, migrated);
                        if (migrated != null) {
                            if (optionalFromContext.isPresent()) {
                                contextFactory.close(optionalFromContext.get(), existingStorage);
                            }
                            updated = true;
                        } else {
                            log.warn("Partition at {} failed to replace with {}, attempting to rewind", coord, destinationStorage);
                            contextFactory.close(toContext, destinationStorage);
                        }
                    } else {
                        log.warn("Partition at {} ignored unsupported storage migration {} to {}", coord, existingStorage, destinationStorage);
                    }
                }
            }
            return updated;
        }
    }

    private MiruPartitionAccessor<BM, IBM, C, S> updatePartition(MiruPartitionAccessor<BM, IBM, C, S> existing,
        MiruPartitionAccessor<BM, IBM, C, S> update) throws Exception {

        synchronized (accessorRef) {
            if (accessorRef.get() != existing) {
                return null;
            }

            heartbeatHandler.heartbeat(coord, Optional.of(update.info), Optional.<Long>absent());

            accessorRef.set(update);

            log.decAtomic("state>" + existing.info.state.name());
            log.decAtomic("storage>" + existing.info.storage.name());
            log.incAtomic("state>" + update.info.state.name());
            log.incAtomic("storage>" + update.info.storage.name());
            if (existing.info.state != MiruPartitionState.bootstrap && update.info.state == MiruPartitionState.bootstrap) {
                bootstrapCounter.inc("Total number of partitions that need to be brought online.",
                    "Be patient. Rebalance. Increase number of concurrent rebuilds.");
            } else if (existing.info.state == MiruPartitionState.bootstrap && update.info.state != MiruPartitionState.bootstrap) {
                bootstrapCounter.dec("Total number of partitions that need to be brought online.",
                    "Be patient. Rebalance. Increase number of concurrent rebuilds.");
            }

            log.info("Partition is now {}/{} for {}", update.info.state, update.info.storage, coord);
            return update;
        }
    }

    protected class BootstrapRunnable implements Runnable {

        private final AtomicLong banUnregisteredSchema = new AtomicLong();

        @Override
        public void run() {
            try {
                try {
                    StackBuffer stackBuffer = new StackBuffer();
                    checkActive(stackBuffer);
                } catch (MiruSchemaUnvailableException sue) {
                    log.warn("Tenant is active but schema not available for {}", coord.tenantId);
                    log.debug("Tenant is active but schema not available", sue);
                } catch (Throwable t) {
                    log.error("CheckActive encountered a problem for {}", new Object[] { coord }, t);
                }
            } catch (Throwable t) {
                log.error("Bootstrap encountered a problem for {}", new Object[] { coord }, t);
            }
        }

        private void checkActive(StackBuffer stackBuffer) throws Exception {
            if (removed.get() || banUnregisteredSchema.get() >= System.currentTimeMillis()) {
                return;
            }

            MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
            MiruPartitionActive partitionActive = heartbeatHandler.getPartitionActive(coord);
            if (partitionActive.destroyAfterTimestamp > 0 && System.currentTimeMillis() > partitionActive.destroyAfterTimestamp) {
                if (accessor.info.state != MiruPartitionState.offline) {
                    log.info("Taking partition offline because it is marked for destruction: {}", coord);
                    close();
                    accessor = accessorRef.get();
                }
                if (accessor.info.state == MiruPartitionState.offline && accessor.info.storage == MiruBackingStorage.disk) {
                    synchronized (factoryLock) {
                        log.info("Cleaning disk for partition because it is marked for destruction: {}", coord);

                        MiruPartitionAccessor<BM, IBM, C, S> existing = accessorRef.get();
                        MiruPartitionCoordInfo coordInfo = existing.info
                            .copyToStorage(MiruBackingStorage.memory)
                            .copyToState(MiruPartitionState.offline);
                        MiruPartitionAccessor<BM, IBM, C, S> cleaned = MiruPartitionAccessor.initialize(miruStats, bitmaps, coord, coordInfo,
                            Optional.<MiruContext<IBM, S>>absent(),
                            indexRepairs, indexer);
                        cleaned = updatePartition(existing, cleaned);
                        if (cleaned != null) {
                            if (existing.context != null) {
                                Optional<MiruContext<IBM, S>> closedContext = existing.close();
                                if (closedContext.isPresent()) {
                                    contextFactory.close(closedContext.get(), existing.info.storage);
                                }
                            }
                            existing.refundChits(mergeChits);
                            clearFutures();
                            contextFactory.cleanDisk(coord);
                            contextFactory.markStorage(coord, MiruBackingStorage.memory);
                        } else {
                            log.warn("Failed to clean disk because accessor changed for partition: {}", coord);
                        }
                    }
                }
            } else if (partitionActive.activeUntilTimestamp > System.currentTimeMillis()) {
                if (accessor.info.state == MiruPartitionState.offline) {
                    if (accessor.info.storage == MiruBackingStorage.memory) {
                        try {
                            open(accessor, accessor.info.copyToState(MiruPartitionState.bootstrap), stackBuffer);
                        } catch (MiruPartitionUnavailableException e) {
                            log.warn("CheckActive: Partition is active for tenant {} but no schema is registered, banning for {} ms",
                                coord.tenantId, timings.partitionBanUnregisteredSchemaMillis);
                            banUnregisteredSchema.set(System.currentTimeMillis() + timings.partitionBanUnregisteredSchemaMillis);
                        }
                    } else if (accessor.info.storage == MiruBackingStorage.disk) {
                        if (!removed.get() && accessor.canHotDeploy()) {
                            log.info("Hot deploying for checkActive: {}", coord);
                            open(accessor, accessor.info.copyToState(MiruPartitionState.online), stackBuffer);
                        }
                    }
                } else if (accessor.context.isPresent() && System.currentTimeMillis() > partitionActive.idleAfterTimestamp) {
                    MiruContext<IBM, S> context = accessor.context.get();
                    contextFactory.releaseCaches(context, accessor.info.storage);
                }
            } else if (accessor.info.state != MiruPartitionState.offline) {
                close();
            }
        }

    }

    protected class RebuildIndexRunnable implements Runnable {

        private final AtomicLong banUnregisteredSchema = new AtomicLong();

        @Override
        public void run() {
            StackBuffer stackBuffer = new StackBuffer();
            try {
                MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
                if (accessor.info.storage != MiruBackingStorage.memory || banUnregisteredSchema.get() >= System.currentTimeMillis()) {
                    return;
                }

                MiruPartitionState state = accessor.info.state;
                if (state == MiruPartitionState.bootstrap || state == MiruPartitionState.rebuilding) {
                    MiruActivityWALStatus status = walClient.getActivityWALStatusForTenant(coord.tenantId, coord.partitionId);
                    long count = -1;
                    for (MiruActivityWALStatus.WriterCount writerCount : status.counts) {
                        count += writerCount.count;
                    }
                    Optional<MiruRebuildDirector.Token> token = rebuildDirector.acquire(coord, count);
                    if (token.isPresent()) {
                        try {
                            MiruPartitionAccessor<BM, IBM, C, S> rebuilding;
                            if (state == MiruPartitionState.bootstrap) {
                                rebuilding = open(accessor, accessor.info.copyToState(MiruPartitionState.rebuilding), stackBuffer);
                                if (rebuilding.info.state != MiruPartitionState.rebuilding) {
                                    log.warn("Failed to transition to rebuilding for {}", coord);
                                    rebuilding = null;
                                }
                            } else {
                                rebuilding = accessor;
                            }
                            if (rebuilding != null) {
                                try {
                                    if (!rebuilding.context.isPresent()) {
                                        log.error("Attempted rebuild without a context for {}", coord);
                                    } else if (rebuilding.context.get().isCorrupt()) {
                                        if (close()) {
                                            log.warn("Stopped rebuild due to corruption for {}", coord);
                                        } else {
                                            log.error("Failed to stop rebuild after corruption for {}", coord);
                                        }
                                    } else if (rebuild(rebuilding, stackBuffer)) {
                                        MiruPartitionAccessor<BM, IBM, C, S> online = rebuilding.copyToState(MiruPartitionState.online);
                                        MiruPartitionAccessor<BM, IBM, C, S> updated = updatePartition(rebuilding, online);
                                        if (updated != null) {
                                            updated.merge(mergeChits, mergeExecutor, trackError, stackBuffer);
                                            trackError.reset();
                                        }
                                    } else {
                                        log.error("Rebuild did not finish for {} isAccessor={}", coord, (rebuilding == accessorRef.get()));
                                    }
                                } catch (Throwable t) {
                                    log.error("Rebuild encountered a problem for {}", new Object[] { coord }, t);
                                }
                            }
                        } catch (MiruPartitionUnavailableException e) {
                            log.warn("Rebuild: Partition is active for tenant {} but no schema is registered, banning for {} ms",
                                coord.tenantId, timings.partitionBanUnregisteredSchemaMillis);
                            banUnregisteredSchema.set(System.currentTimeMillis() + timings.partitionBanUnregisteredSchemaMillis);
                        } finally {
                            rebuildDirector.release(token.get());
                        }
                    } else {
                        log.debug("Skipped rebuild because count={} available={}", count, rebuildDirector.available());
                    }
                }
            } catch (MiruSchemaUnvailableException e) {
                log.warn("Skipped rebuild because schema is unavailable for {}", coord);
            } catch (Throwable t) {
                log.error("RebuildIndex encountered a problem for {}", new Object[] { coord }, t);
            }
        }

        private boolean rebuild(final MiruPartitionAccessor<BM, IBM, C, S> accessor, StackBuffer stackBuffer) throws Exception {
            final ArrayBlockingQueue<MiruWALClient.StreamBatch<MiruWALEntry, C>> queue = new ArrayBlockingQueue<>(1);
            final AtomicReference<C> cursor = new AtomicReference<>(accessor.getRebuildCursor());
            final AtomicBoolean rebuilding = new AtomicBoolean(true);
            final AtomicBoolean endOfWAL = new AtomicBoolean(false);

            log.debug("Starting rebuild at {} for {}", cursor.get(), coord);

            rebuildWALExecutors.submit(() -> {
                try {

                    MiruWALClient.StreamBatch<MiruWALEntry, C> streamBatch = walClient.getActivity(coord.tenantId,
                        coord.partitionId,
                        cursor.get(),
                        partitionRebuildBatchSize);

                    while (rebuilding.get() && accessorRef.get() == accessor && streamBatch != null) {
                        tryQueuePut(rebuilding, queue, streamBatch);
                        if (streamBatch.activities.isEmpty()) {
                            break;
                        }
                        streamBatch = (streamBatch.cursor != null)
                            ? walClient.getActivity(coord.tenantId, coord.partitionId, streamBatch.cursor, partitionRebuildBatchSize)
                            : null;
                    }

                    // signals end of rebuild
                    log.debug("Signaling end of rebuild for {}", coord);
                    endOfWAL.set(true);
                } catch (Exception x) {
                    log.error("Failure while rebuilding {}", new Object[] { coord }, x);
                } finally {
                    rebuilding.set(false);
                }
            });

            final ExecutorService rebuildIndexExecutor = Executors.newFixedThreadPool(rebuildIndexerThreads,
                new NamedThreadFactory(Thread.currentThread().getThreadGroup(), "rebuild_index_" + coord.tenantId + "_" + coord.partitionId));

            Exception failure = null;
            try {
                int totalIndexed = 0;
                while (true) {
                    MiruWALClient.StreamBatch<MiruWALEntry, C> streamBatch = null;
                    List<MiruPartitionedActivity> partitionedActivities = null;
                    C nextCursor = null;
                    while ((rebuilding.get() || !queue.isEmpty()) && streamBatch == null) {
                        streamBatch = queue.poll(1, TimeUnit.SECONDS);
                    }

                    if (streamBatch != null) {
                        partitionedActivities = new ArrayList<>(streamBatch.activities.size());
                        for (MiruWALEntry batch : streamBatch.activities) {
                            partitionedActivities.add(batch.activity);
                        }
                        nextCursor = streamBatch.cursor;
                    } else {
                        // end of rebuild
                        log.debug("Ending rebuild for {}", coord);
                        break;
                    }

                    int count = partitionedActivities.size();
                    totalIndexed += count;

                    log.debug("Indexing batch of size {} (total {}) for {}", count, totalIndexed, coord);
                    log.startTimer("rebuild>batchSize-" + partitionRebuildBatchSize);
                    boolean repair = firstRebuild.compareAndSet(true, false);
                    accessor.indexInternal(partitionedActivities.iterator(),
                        MiruPartitionAccessor.IndexStrategy.rebuild,
                        repair,
                        mergeChits,
                        rebuildIndexExecutor,
                        mergeExecutor,
                        trackError,
                        stackBuffer);
                    accessor.merge(mergeChits, mergeExecutor, trackError, stackBuffer);
                    accessor.setRebuildCursor(nextCursor);
                    if (nextCursor.getSipCursor() != null) {
                        accessor.setSip(nextCursor.getSipCursor(), stackBuffer);
                    }

                    log.stopTimer("rebuild>batchSize-" + partitionRebuildBatchSize);
                    log.inc("rebuild>count>calls", 1);
                    log.inc("rebuild>count>total", count);
                    log.inc("rebuild>count>power>" + FilerIO.chunkPower(count, 0), 1);
                    log.inc("rebuild", count, coord.tenantId.toString());
                    log.inc("rebuild>partition>" + coord.partitionId, count, coord.tenantId.toString());
                }
            } catch (Exception e) {
                log.error("Failure during rebuild index for {}", new Object[] { coord }, e);
                failure = e;
            }

            rebuilding.set(false);
            boolean shutdown = false;
            boolean interrupted = false;
            while (!shutdown) {
                rebuildIndexExecutor.shutdown();
                try {
                    shutdown = rebuildIndexExecutor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    log.warn("Ignored interrupt while awaiting shutdown of rebuild index executor for {}", coord);
                    interrupted = true;
                }
            }
            if (interrupted) {
                if (failure != null) {
                    failure = new InterruptedException("Interrupted awaiting rebuild index executor termination after failure: " + failure.getMessage());
                } else {
                    failure = new InterruptedException("Interrupted awaiting rebuild index executor termination");
                }
            }
            if (failure != null) {
                throw failure;
            }

            return endOfWAL.get() && accessorRef.get() == accessor;
        }

        private boolean tryQueuePut(AtomicBoolean rebuilding,
            ArrayBlockingQueue<MiruWALClient.StreamBatch<MiruWALEntry, C>> queue,
            MiruWALClient.StreamBatch<MiruWALEntry, C> batch)
            throws InterruptedException {
            boolean success = false;
            while (rebuilding.get() && !success) {
                success = queue.offer(batch, 1, TimeUnit.SECONDS);
            }
            return success;
        }
    }

    protected class SipMigrateIndexRunnable implements Runnable {

        @Override
        public void run() {
            StackBuffer stackBuffer = new StackBuffer();
            try {
                MiruPartitionAccessor<BM, IBM, C, S> accessor = accessorRef.get();
                MiruPartitionState state = accessor.info.state;
                if (state == MiruPartitionState.online) {
                    boolean forceRebuild = false;
                    MiruActivityWALStatus status = null;
                    if (!accessor.context.isPresent()) {
                        log.info("Forcing rebuild because context is missing for {}", coord);
                        forceRebuild = true;
                    } else if (accessor.context.get().isCorrupt()) {
                        log.info("Forcing rebuild because context is corrupt for {}", coord);
                        forceRebuild = true;
                    } else if (firstSip.get()) {
                        status = walClient.getActivityWALStatusForTenant(coord.tenantId, coord.partitionId);
                    }

                    if (forceRebuild) {
                        updateStorage(accessor, MiruBackingStorage.memory, true, stackBuffer);
                        return;
                    }

                    try {
                        if (accessor.canAutoMigrate()) {
                            updateStorage(accessor, MiruBackingStorage.disk, false, stackBuffer);
                        }
                    } catch (Throwable t) {
                        log.error("Migrate encountered a problem for {}", new Object[] { coord }, t);
                    }
                    try {
                        if (accessor.isOpenForWrites() && accessor.hasOpenWriters()) {
                            int maxWriterId = -1;
                            if (status != null) {
                                for (MiruActivityWALStatus.WriterCount writerCount : status.counts) {
                                    maxWriterId = Math.max(maxWriterId, writerCount.writerId);
                                }
                            }
                            int[] counts = sip(accessor, maxWriterId, stackBuffer);
                            if (counts != null && status != null) {
                                for (MiruActivityWALStatus.WriterCount writerCount : status.counts) {
                                    int currentCount = counts[writerCount.writerId];
                                    if (currentCount > 0) {
                                        int behindByCount = writerCount.count - currentCount;
                                        if (behindByCount > partitionRebuildIfBehindByCount) {
                                            log.info("Forcing rebuild because partition is behind by {} for {}", behindByCount, coord);
                                            updateStorage(accessor, MiruBackingStorage.memory, true, stackBuffer);
                                            return;
                                        }
                                    }
                                }
                            }
                        } else {
                            accessor.refundChits(mergeChits);
                        }
                    } catch (Throwable t) {
                        log.error("Sip encountered a problem for {}", new Object[] { coord }, t);
                    }
                }
            } catch (Throwable t) {
                log.error("SipMigrateIndex encountered a problem for {}", new Object[] { coord }, t);
            }
        }

        private final MiruTenantId GLOBAL_TENANT = new MiruTenantId("global".getBytes(Charsets.UTF_8));

        private int[] sip(final MiruPartitionAccessor<BM, IBM, C, S> accessor, int maxWriterId, StackBuffer stackBuffer) throws Exception {

            final MiruSipTracker<S> sipTracker = sipTrackerFactory.create(accessor.seenLastSip.get());

            S sipCursor = accessor.getSipCursor(stackBuffer).orNull();
            boolean first = firstSip.get();
            int[] counts = new int[maxWriterId + 1];
            Arrays.fill(counts, -1);

            MiruWALClient.StreamBatch<MiruWALEntry, S> sippedActivity = walClient.sipActivity(coord.tenantId,
                coord.partitionId,
                sipCursor,
                sipTracker.getSeenLastSip(),
                partitionSipBatchSize);

            while (accessorRef.get() == accessor && sippedActivity != null) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Interrupted while streaming sip");
                }

                if (sippedActivity.suppressed != null) {
                    for (TimeAndVersion timeAndVersion : sippedActivity.suppressed) {
                        sipTracker.addSeenThisSip(timeAndVersion);
                    }
                }

                List<MiruPartitionedActivity> partitionedActivities = Lists.newArrayListWithCapacity(sippedActivity.activities.size());
                for (MiruWALEntry e : sippedActivity.activities) {
                    long version = e.activity.activity.isPresent() ? e.activity.activity.get().version : 0; // Smells!
                    TimeAndVersion timeAndVersion = new TimeAndVersion(e.activity.timestamp, version);

                    if (!sipTracker.wasSeenLastSip(timeAndVersion)) {
                        partitionedActivities.add(e.activity);
                    }
                    sipTracker.addSeenThisSip(timeAndVersion);
                    sipTracker.track(e.activity);

                    if (counts.length > e.activity.writerId) {
                        counts[e.activity.writerId] = Math.max(counts[e.activity.writerId], e.activity.index);
                    }
                }

                S lastCursor = sipCursor;
                sipCursor = deliver(partitionedActivities, accessor, sipTracker, sipCursor, sippedActivity.cursor, stackBuffer);
                partitionedActivities.clear();

                if (sippedActivity.cursor != null && sippedActivity.cursor.endOfStream()) {
                    if (GLOBAL_TENANT.equals(coord.tenantId)) {
                        log.info("Sipped to end of stream for {}", coord);
                    }
                    long threshold = first ? 0 : timings.partitionSipNotifyEndOfStreamMillis;
                    accessor.notifyEndOfStream(threshold);
                    break;
                } else if (sipCursor == null) {
                    if (GLOBAL_TENANT.equals(coord.tenantId)) {
                        log.info("No cursor for {}", coord);
                    }
                    break;
                } else if (sipCursor.equals(lastCursor)) {
                    if (GLOBAL_TENANT.equals(coord.tenantId)) {
                        log.info("Sipped same cursor for {}", coord);
                    }
                    break;
                } else if (sippedActivity.endOfWAL) {
                    if (GLOBAL_TENANT.equals(coord.tenantId)) {
                        log.info("Sipped to end of WAL for {}", coord);
                    }
                    break;
                }

                sippedActivity = walClient.sipActivity(coord.tenantId, coord.partitionId, sipCursor, sipTracker.getSeenThisSip(), partitionSipBatchSize);
            }

            if (!accessor.hasOpenWriters()) {
                accessor.merge(mergeChits, mergeExecutor, trackError, stackBuffer);
            }

            if (accessorRef.get() == accessor) {
                return counts;
            } else {
                return null;
            }
        }

        private S deliver(final List<MiruPartitionedActivity> partitionedActivities, final MiruPartitionAccessor<BM, IBM, C, S> accessor,
            final MiruSipTracker<S> sipTracker, S sipCursor, S nextSipCursor, StackBuffer stackBuffer) throws Exception {
            boolean repair = firstSip.compareAndSet(true, false);
            int initialCount = partitionedActivities.size();
            int count = accessor.indexInternal(partitionedActivities.iterator(),
                MiruPartitionAccessor.IndexStrategy.sip,
                repair,
                mergeChits,
                sipIndexExecutor,
                mergeExecutor,
                trackError,
                stackBuffer);

            S suggestion = sipTracker.suggest(sipCursor, nextSipCursor);
            if (suggestion != null && accessor.setSip(suggestion, stackBuffer)) {
                accessor.seenLastSip.compareAndSet(sipTracker.getSeenLastSip(), sipTracker.getSeenThisSip());
                sipTracker.metrics(coord, suggestion);
            }

            log.inc("sip>count>calls", 1);
            if (count > 0) {
                log.inc("sip>count>total", count);
                log.inc("sip>count>power>" + FilerIO.chunkPower(count, 0), 1);
                log.inc("sip>count", count, coord.tenantId.toString());
                log.inc("sip>partition>" + coord.partitionId, count, coord.tenantId.toString());
            }
            if (initialCount > 0) {
                log.inc("sip>count>skip", (initialCount - count));
            }
            return suggestion;
        }

    }

    @Override
    public String toString() {
        return "MiruLocalHostedPartition{"
            + "coord=" + coord
            + ", accessorRef=" + accessorRef.get()
            + '}';
    }

    public static final class Timings {

        private final long partitionBootstrapIntervalInMillis;
        private final long partitionRebuildIntervalInMillis;
        private final long partitionSipMigrateIntervalInMillis;
        private final long partitionBanUnregisteredSchemaMillis;
        private final long partitionMigrationWaitInMillis;
        private final long partitionSipNotifyEndOfStreamMillis;

        public Timings(long partitionBootstrapIntervalInMillis,
            long partitionRebuildIntervalInMillis,
            long partitionSipMigrateIntervalInMillis,
            long partitionBanUnregisteredSchemaMillis,
            long partitionMigrationWaitInMillis,
            long partitionSipNotifyEndOfStreamMillis) {
            this.partitionBootstrapIntervalInMillis = partitionBootstrapIntervalInMillis;
            this.partitionRebuildIntervalInMillis = partitionRebuildIntervalInMillis;
            this.partitionSipMigrateIntervalInMillis = partitionSipMigrateIntervalInMillis;
            this.partitionBanUnregisteredSchemaMillis = partitionBanUnregisteredSchemaMillis;
            this.partitionMigrationWaitInMillis = partitionMigrationWaitInMillis;
            this.partitionSipNotifyEndOfStreamMillis = partitionSipNotifyEndOfStreamMillis;
        }
    }

}
