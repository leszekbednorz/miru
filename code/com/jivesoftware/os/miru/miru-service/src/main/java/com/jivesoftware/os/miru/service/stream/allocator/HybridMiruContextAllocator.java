package com.jivesoftware.os.miru.service.stream.allocator;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jivesoftware.os.filer.chunk.store.ChunkStore;
import com.jivesoftware.os.filer.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.filer.io.ByteArrayPartitionFunction;
import com.jivesoftware.os.filer.io.ByteBufferFactory;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.io.primative.LongIntKeyValueMarshaller;
import com.jivesoftware.os.filer.keyed.store.KeyedFilerStore;
import com.jivesoftware.os.filer.keyed.store.TxKeyObjectStore;
import com.jivesoftware.os.filer.keyed.store.TxKeyValueStore;
import com.jivesoftware.os.filer.keyed.store.TxKeyedFilerStore;
import com.jivesoftware.os.filer.keyed.store.TxPartitionedKeyObjectStore;
import com.jivesoftware.os.filer.map.store.PassThroughKeyMarshaller;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionState;
import com.jivesoftware.os.miru.api.activity.schema.MiruFieldDefinition;
import com.jivesoftware.os.miru.api.activity.schema.MiruSchema;
import com.jivesoftware.os.miru.api.base.MiruStreamId;
import com.jivesoftware.os.miru.api.base.MiruTermId;
import com.jivesoftware.os.miru.api.field.MiruFieldType;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.index.MiruActivityInternExtern;
import com.jivesoftware.os.miru.plugin.index.MiruFieldIndexProvider;
import com.jivesoftware.os.miru.plugin.schema.MiruSchemaProvider;
import com.jivesoftware.os.miru.service.index.KeyedFilerProvider;
import com.jivesoftware.os.miru.service.index.MiruInternalActivityMarshaller;
import com.jivesoftware.os.miru.service.index.auth.MiruAuthzCache;
import com.jivesoftware.os.miru.service.index.auth.MiruAuthzUtils;
import com.jivesoftware.os.miru.service.index.auth.VersionedAuthzExpression;
import com.jivesoftware.os.miru.service.index.disk.MiruChunkActivityIndex;
import com.jivesoftware.os.miru.service.index.disk.MiruOnDiskFieldIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryAuthzIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryInboxIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryRemovalIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryTimeIndex;
import com.jivesoftware.os.miru.service.index.memory.MiruInMemoryUnreadTrackingIndex;
import com.jivesoftware.os.miru.service.index.memory.ReadWrite;
import com.jivesoftware.os.miru.service.locator.MiruHybridResourceLocator;
import com.jivesoftware.os.miru.service.locator.MiruResourcePartitionIdentifier;
import com.jivesoftware.os.miru.service.stream.MiruContext;
import com.jivesoftware.os.miru.wal.readtracking.MiruReadTrackingWALReader;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class HybridMiruContextAllocator implements MiruContextAllocator {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final MiruSchemaProvider schemaProvider;
    private final MiruActivityInternExtern activityInternExtern;
    private final MiruReadTrackingWALReader readTrackingWALReader;
    private final MiruHybridResourceLocator hybridResourceLocator;
    private final ByteBufferFactory byteBufferFactory;
    private final int numberOfChunkStores;
    private final int partitionAuthzCacheSize;
    private final boolean partitionDeleteChunkStoreOnClose;
    private final StripingLocksProvider<MiruTermId> fieldIndexStripingLocksProvider;
    private final StripingLocksProvider<Long> chunkStripingLocksProvider;

    public HybridMiruContextAllocator(MiruSchemaProvider schemaProvider,
        MiruActivityInternExtern activityInternExtern,
        MiruReadTrackingWALReader readTrackingWALReader,
        MiruHybridResourceLocator hybridResourceLocator,
        ByteBufferFactory byteBufferFactory,
        int numberOfChunkStores,
        int partitionAuthzCacheSize,
        boolean partitionDeleteChunkStoreOnClose,
        StripingLocksProvider<MiruTermId> fieldIndexStripingLocksProvider,
        StripingLocksProvider<Long> chunkStripingLocksProvider) {
        this.schemaProvider = schemaProvider;
        this.activityInternExtern = activityInternExtern;
        this.readTrackingWALReader = readTrackingWALReader;
        this.hybridResourceLocator = hybridResourceLocator;
        this.byteBufferFactory = byteBufferFactory;
        this.numberOfChunkStores = numberOfChunkStores;
        this.partitionAuthzCacheSize = partitionAuthzCacheSize;
        this.partitionDeleteChunkStoreOnClose = partitionDeleteChunkStoreOnClose;
        this.fieldIndexStripingLocksProvider = fieldIndexStripingLocksProvider;
        this.chunkStripingLocksProvider = chunkStripingLocksProvider;
    }

    @Override
    public boolean checkMarkedStorage(MiruPartitionCoord coord) throws Exception {
        return true;
    }

    @Override
    public <BM> MiruContext<BM> allocate(MiruBitmaps<BM> bitmaps, MiruPartitionCoord coord) throws Exception {
        // check for schema first
        MiruSchema schema = schemaProvider.getSchema(coord.tenantId);

        ChunkStore[] chunkStores = new ChunkStore[numberOfChunkStores];
        ChunkStoreInitializer chunkStoreInitializer = new ChunkStoreInitializer();
        long initialChunkSize = hybridResourceLocator.getInitialChunkSize();
        for (int i = 0; i < numberOfChunkStores; i++) {
            chunkStores[i] = chunkStoreInitializer.create(byteBufferFactory, initialChunkSize, chunkStripingLocksProvider);
        }

        return buildMiruContext(bitmaps, schema, chunkStores);
    }

    @Override
    public <BM> MiruContext<BM> stateChanged(MiruBitmaps<BM> bitmaps, MiruPartitionCoord coord, MiruContext<BM> from, MiruPartitionState state)
        throws Exception {

        if (state != MiruPartitionState.online) {
            return from;
        }

        MiruSchema schema = from.schema;

        MiruResourcePartitionIdentifier identifier = hybridResourceLocator.acquire();

        ChunkStore[] fromChunkStores = from.chunkStores.get();

        File[] chunkDirs = hybridResourceLocator.getChunkDirectories(identifier, "chunks");
        StripingLocksProvider<Long> locksProvider = new StripingLocksProvider<>(64);
        ChunkStore[] chunkStores = new ChunkStore[numberOfChunkStores];
        ChunkStoreInitializer chunkStoreInitializer = new ChunkStoreInitializer();
        for (int i = 0; i < numberOfChunkStores; i++) {
            chunkStores[i] = chunkStoreInitializer.openOrCreate(
                chunkDirs,
                "chunk-" + i,
                hybridResourceLocator.getInitialChunkSize(),
                locksProvider);
            fromChunkStores[i].copyTo(chunkStores[i]);
        }

        MiruContext<BM> miruContext = buildMiruContext(bitmaps, schema, chunkStores);

        LOG.info("Updated context when hybrid state changed to {}", state);
        return miruContext;
    }

    private <BM> MiruContext<BM> buildMiruContext(MiruBitmaps<BM> bitmaps, MiruSchema schema, ChunkStore[] chunkStores)
        throws Exception {

        MiruInMemoryTimeIndex timeIndex = new MiruInMemoryTimeIndex(
            Optional.<MiruInMemoryTimeIndex.TimeOrderAnomalyStream>absent(),
            new TxKeyValueStore<>(chunkStores,
                new LongIntKeyValueMarshaller(),
                keyBytes("timeIndex"),
                8, false, 4, false));

        TxKeyedFilerStore activityKeyedFilerStore = new TxKeyedFilerStore(chunkStores, keyBytes("activityIndex-map"));
        MiruChunkActivityIndex activityIndex = new MiruChunkActivityIndex(
            activityKeyedFilerStore,
            new MiruInternalActivityMarshaller(),
            new KeyedFilerProvider(activityKeyedFilerStore, keyBytes("activityIndex-size")));

        @SuppressWarnings("unchecked")
        MiruOnDiskFieldIndex<BM>[] fieldIndexes = new MiruOnDiskFieldIndex[MiruFieldType.values().length];
        for (MiruFieldType fieldType : MiruFieldType.values()) {
            KeyedFilerStore[] indexes = new KeyedFilerStore[schema.fieldCount()];
            for (MiruFieldDefinition fieldDefinition : schema.getFieldDefinitions()) {
                int fieldId = fieldDefinition.fieldId;
                if (fieldType == MiruFieldType.latest && !fieldDefinition.indexLatest
                    || fieldType == MiruFieldType.pairedLatest && fieldDefinition.pairedLatestFieldNames.isEmpty()
                    || fieldType == MiruFieldType.bloom && fieldDefinition.bloomFieldNames.isEmpty()) {
                    indexes[fieldId] = null;
                } else {
                    indexes[fieldId] = new TxKeyedFilerStore(chunkStores, keyBytes("field-" + fieldType.name() + "-" + fieldId));
                }
            }
            fieldIndexes[fieldType.getIndex()] = new MiruOnDiskFieldIndex<>(bitmaps, indexes, fieldIndexStripingLocksProvider);
        }
        MiruFieldIndexProvider<BM> fieldIndexProvider = new MiruFieldIndexProvider<>(fieldIndexes);

        MiruAuthzUtils<BM> authzUtils = new MiruAuthzUtils<>(bitmaps);

        //TODO share the cache?
        Cache<VersionedAuthzExpression, BM> authzCache = CacheBuilder.newBuilder()
            .maximumSize(partitionAuthzCacheSize)
            .expireAfterAccess(1, TimeUnit.MINUTES) //TODO should be adjusted with respect to tuning GC (prevent promotion from eden space)
            .build();

        TxKeyObjectStore<byte[], ReadWrite<BM>>[] authZPartitiones = new TxKeyObjectStore[chunkStores.length];
        for (int i = 0; i < chunkStores.length; i++) {
            authZPartitiones[i] = new TxKeyObjectStore<>(chunkStores[i],
                PassThroughKeyMarshaller.INSTANCE,
                keyBytes("authzIndex"),
                10, //TODO expose to config
                128, //TODO ditto
                true);
        }

        MiruInMemoryAuthzIndex<BM> authzIndex = new MiruInMemoryAuthzIndex<>(
            bitmaps,
            new TxPartitionedKeyObjectStore<>(new ByteArrayPartitionFunction(), authZPartitiones),
            new MiruAuthzCache<>(bitmaps, authzCache, activityInternExtern, authzUtils));

        TxKeyObjectStore<byte[], ReadWrite<BM>>[] removealIndexPartitiones = new TxKeyObjectStore[chunkStores.length];
        for (int i = 0; i < chunkStores.length; i++) {
            removealIndexPartitiones[i] = new TxKeyObjectStore<>(chunkStores[i],
                PassThroughKeyMarshaller.INSTANCE,
                keyBytes("removalIndex"),
                2,
                1,
                false);
        }

        MiruInMemoryRemovalIndex<BM> removalIndex = new MiruInMemoryRemovalIndex<>(
            bitmaps,
            new TxPartitionedKeyObjectStore<>(new ByteArrayPartitionFunction(), removealIndexPartitiones),
            new byte[] { 0 },
            -1);

        TxKeyObjectStore<byte[], ReadWrite<BM>>[] unreadTrackingIndexPartitiones = new TxKeyObjectStore[chunkStores.length];
        for (int i = 0; i < chunkStores.length; i++) {
            unreadTrackingIndexPartitiones[i] = new TxKeyObjectStore<>(chunkStores[i],
                PassThroughKeyMarshaller.INSTANCE,
                keyBytes("unreadTrackingIndex"),
                10, //TODO expose to config
                16, //TODO ditto (except expose to schema instead)
                true);
        }

        MiruInMemoryUnreadTrackingIndex<BM> unreadTrackingIndex = new MiruInMemoryUnreadTrackingIndex<>(bitmaps,
            new TxPartitionedKeyObjectStore<>(new ByteArrayPartitionFunction(), unreadTrackingIndexPartitiones));

        TxKeyObjectStore<byte[], ReadWrite<BM>>[] inboxIndexPartitiones = new TxKeyObjectStore[chunkStores.length];
        for (int i = 0; i < chunkStores.length; i++) {
            inboxIndexPartitiones[i] = new TxKeyObjectStore<>(chunkStores[i],
                PassThroughKeyMarshaller.INSTANCE,
                keyBytes("inboxIndex"),
                10, //TODO expose to config
                16, //TODO ditto (except expose to schema instead)
                true);
        }
        MiruInMemoryInboxIndex<BM> inboxIndex = new MiruInMemoryInboxIndex<>(bitmaps,
            new TxPartitionedKeyObjectStore<>(new ByteArrayPartitionFunction(), inboxIndexPartitiones));

        StripingLocksProvider<MiruStreamId> streamLocks = new StripingLocksProvider<>(64);

        return new MiruContext<>(schema,
            timeIndex,
            activityIndex,
            fieldIndexProvider,
            authzIndex,
            removalIndex,
            unreadTrackingIndex,
            inboxIndex,
            readTrackingWALReader,
            activityInternExtern,
            streamLocks,
            Optional.of(chunkStores),
            Optional.<MiruResourcePartitionIdentifier>absent());
    }

    @Override
    public <BM> void close(MiruContext<BM> context) {
        if (context.chunkStores.isPresent() && partitionDeleteChunkStoreOnClose) {
            ChunkStore[] chunkStores = context.chunkStores.get();
            for (ChunkStore chunkStore : chunkStores) {
                try {
                    chunkStore.delete();
                } catch (IOException e) {
                    LOG.warn("Failed to delete chunk store", e);
                }
            }
        }
    }

    private byte[] keyBytes(String key) {
        return key.getBytes(Charsets.UTF_8);
    }

    private String[] filesToPaths(File[] files) {
        String[] paths = new String[files.length];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = files[i].getAbsolutePath();
        }
        return paths;
    }

}
