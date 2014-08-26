package com.jivesoftware.os.miru.service;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.miru.api.MiruBackingStorage;
import com.jivesoftware.os.miru.api.MiruHost;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.MiruPartitionCoordInfo;
import com.jivesoftware.os.miru.api.activity.MiruPartitionId;
import com.jivesoftware.os.miru.api.activity.MiruPartitionedActivity;
import com.jivesoftware.os.miru.api.base.MiruTenantId;
import com.jivesoftware.os.miru.api.query.AggregateCountsQuery;
import com.jivesoftware.os.miru.api.query.DistinctCountQuery;
import com.jivesoftware.os.miru.api.query.RecoQuery;
import com.jivesoftware.os.miru.api.query.TrendingQuery;
import com.jivesoftware.os.miru.api.query.result.AggregateCountsResult;
import com.jivesoftware.os.miru.api.query.result.DistinctCountResult;
import com.jivesoftware.os.miru.api.query.result.RecoResult;
import com.jivesoftware.os.miru.api.query.result.TrendingResult;
import com.jivesoftware.os.miru.cluster.MiruActivityLookupTable;
import com.jivesoftware.os.miru.service.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.service.partition.MiruHostedPartition;
import com.jivesoftware.os.miru.service.partition.MiruHostedPartitionComparison;
import com.jivesoftware.os.miru.service.partition.MiruPartitionDirector;
import com.jivesoftware.os.miru.service.partition.OrderedPartitions;
import com.jivesoftware.os.miru.service.query.merge.MergeAggregateCountResults;
import com.jivesoftware.os.miru.service.query.merge.MergeDistinctCountResults;
import com.jivesoftware.os.miru.service.query.merge.MergeRecoResults;
import com.jivesoftware.os.miru.service.query.merge.MergeTrendingResults;
import com.jivesoftware.os.miru.service.query.merge.MiruResultMerger;
import com.jivesoftware.os.miru.service.stream.factory.AggregateCountsExecuteQueryCallableFactory;
import com.jivesoftware.os.miru.service.stream.factory.AggregateCountsResultEvaluator;
import com.jivesoftware.os.miru.service.stream.factory.CountCustomExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.CountInboxExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.DistinctCountExecuteQueryCallableFactory;
import com.jivesoftware.os.miru.service.stream.factory.DistinctCountResultEvaluator;
import com.jivesoftware.os.miru.service.stream.factory.ExecuteQueryCallableFactory;
import com.jivesoftware.os.miru.service.stream.factory.FilterCustomExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.FilterInboxExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.MiruFilterUtils;
import com.jivesoftware.os.miru.service.stream.factory.MiruJustInTimeBackfillerizer;
import com.jivesoftware.os.miru.service.stream.factory.MiruResultEvaluator;
import com.jivesoftware.os.miru.service.stream.factory.MiruSolution;
import com.jivesoftware.os.miru.service.stream.factory.MiruSolvable;
import com.jivesoftware.os.miru.service.stream.factory.MiruSolver;
import com.jivesoftware.os.miru.service.stream.factory.RecoExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.RecoExecuteQueryCallableFactory;
import com.jivesoftware.os.miru.service.stream.factory.RecoResultEvaluator;
import com.jivesoftware.os.miru.service.stream.factory.TrendingExecuteQuery;
import com.jivesoftware.os.miru.service.stream.factory.TrendingExecuteQueryCallableFactory;
import com.jivesoftware.os.miru.service.stream.factory.TrendingResultEvaluator;
import com.jivesoftware.os.miru.wal.activity.MiruActivityWALWriter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Singleton;

/** @author jonathan */
@Singleton
public class MiruService<BM> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final MiruHost localhost;
    private final MiruPartitionDirector partitionDirector;
    private final MiruFilterUtils filterUtils;
    private final MiruJustInTimeBackfillerizer backfillerizer;
    private final MiruSolver solver;
    private final MiruHostedPartitionComparison partitionComparison;
    private final MiruActivityWALWriter activityWALWriter;
    private final MiruActivityLookupTable activityLookupTable;
    private final MiruBitmaps<BM> bitmaps;

    public MiruService(MiruHost localhost,
        Executor executor,
        ScheduledExecutorService scheduledExecutorService,
        MiruJustInTimeBackfillerizer backfillerizer,
        MiruPartitionDirector partitionDirector,
        MiruHostedPartitionComparison partitionComparison,
        MiruActivityWALWriter activityWALWriter,
        MiruActivityLookupTable activityLookupTable,
        MiruSolver solver,
        MiruBitmaps<BM> bitmaps,
        MiruFilterUtils miruFilterUtils) {
        this.localhost = localhost;
        this.partitionDirector = partitionDirector;
        this.partitionComparison = partitionComparison;
        this.activityWALWriter = activityWALWriter;
        this.activityLookupTable = activityLookupTable;
        this.filterUtils = miruFilterUtils;
        this.backfillerizer = backfillerizer;
        this.solver = solver;
        this.bitmaps = bitmaps;


    }

    public void writeToIndex(List<MiruPartitionedActivity> partitionedActivities) throws Exception {
        ListMultimap<MiruTenantId, MiruPartitionedActivity> perTenantPartitionedActivites = ArrayListMultimap.create();
        for (MiruPartitionedActivity partitionedActivity : partitionedActivities) {
            perTenantPartitionedActivites.put(partitionedActivity.tenantId, partitionedActivity);
        }
        partitionDirector.index(perTenantPartitionedActivites);
    }

    public void writeWAL(List<MiruPartitionedActivity> partitionedActivities) throws Exception {
        ListMultimap<MiruTenantId, MiruPartitionedActivity> perTenantPartitionedActivites = ArrayListMultimap.create();
        for (MiruPartitionedActivity partitionedActivity : partitionedActivities) {
            perTenantPartitionedActivites.put(partitionedActivity.tenantId, partitionedActivity);
        }
        for (MiruTenantId tenantId : perTenantPartitionedActivites.keySet()) {
            List<MiruPartitionedActivity> tenantPartitionedActivities = perTenantPartitionedActivites.get(tenantId);
            activityWALWriter.write(tenantId, tenantPartitionedActivities);
            activityLookupTable.add(tenantId, tenantPartitionedActivities);
        }
    }

    public long sizeInBytes() {
        return -1;
    }

    /** Filter streams across all partitions */
    public AggregateCountsResult filterCustomStream(final AggregateCountsQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterCustomExecuteQuery<>(bitmaps, filterUtils, query)),
            new AggregateCountsResultEvaluator(query),
            new MergeAggregateCountResults(),
            AggregateCountsResult.EMPTY_RESULTS);
    }

    public AggregateCountsResult filterInboxStreamAll(AggregateCountsQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, false)),
            new AggregateCountsResultEvaluator(query),
            new MergeAggregateCountResults(),
            AggregateCountsResult.EMPTY_RESULTS);
    }

    public AggregateCountsResult filterInboxStreamUnread(AggregateCountsQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, true)),
            new AggregateCountsResultEvaluator(query),
            new MergeAggregateCountResults(),
            AggregateCountsResult.EMPTY_RESULTS);
    }

    /** Filter streams for a specific partition */
    public AggregateCountsResult filterCustomStream(MiruPartitionId partitionId, AggregateCountsQuery query, Optional<AggregateCountsResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterCustomExecuteQuery<>(bitmaps, filterUtils, query)),
            lastResult, AggregateCountsResult.EMPTY_RESULTS);
    }

    public AggregateCountsResult filterInboxStreamAll(MiruPartitionId partitionId, AggregateCountsQuery query, Optional<AggregateCountsResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, false)),
            lastResult, AggregateCountsResult.EMPTY_RESULTS);
    }

    public AggregateCountsResult filterInboxStreamUnread(MiruPartitionId partitionId, AggregateCountsQuery query, Optional<AggregateCountsResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new AggregateCountsExecuteQueryCallableFactory(new FilterInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, true)),
            lastResult, AggregateCountsResult.EMPTY_RESULTS);
    }

    /** Count streams across all partitions */
    public DistinctCountResult countCustomStream(DistinctCountQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new DistinctCountExecuteQueryCallableFactory(new CountCustomExecuteQuery<>(bitmaps, filterUtils, query)),
            new DistinctCountResultEvaluator(query),
            new MergeDistinctCountResults(),
            DistinctCountResult.EMPTY_RESULTS);
    }

    public DistinctCountResult countInboxStreamAll(DistinctCountQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new DistinctCountExecuteQueryCallableFactory(new CountInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, false)),
            new DistinctCountResultEvaluator(query),
            new MergeDistinctCountResults(),
            DistinctCountResult.EMPTY_RESULTS);
    }

    public DistinctCountResult countInboxStreamUnread(DistinctCountQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new DistinctCountExecuteQueryCallableFactory(new CountInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, true)),
            new DistinctCountResultEvaluator(query),
            new MergeDistinctCountResults(),
            DistinctCountResult.EMPTY_RESULTS);
    }

    /** Count streams for a specific partition */
    public DistinctCountResult countCustomStream(MiruPartitionId partitionId, DistinctCountQuery query, Optional<DistinctCountResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new DistinctCountExecuteQueryCallableFactory(new CountCustomExecuteQuery<>(bitmaps, filterUtils, query)),
            lastResult, DistinctCountResult.EMPTY_RESULTS);
    }

    public DistinctCountResult countInboxStreamAll(MiruPartitionId partitionId, DistinctCountQuery query, Optional<DistinctCountResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new DistinctCountExecuteQueryCallableFactory(new CountInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, false)),
            lastResult, DistinctCountResult.EMPTY_RESULTS);
    }

    public DistinctCountResult countInboxStreamUnread(MiruPartitionId partitionId, DistinctCountQuery query, Optional<DistinctCountResult> lastResult)
        throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new DistinctCountExecuteQueryCallableFactory(new CountInboxExecuteQuery<>(bitmaps, filterUtils, backfillerizer, query, true)),
            lastResult, DistinctCountResult.EMPTY_RESULTS);
    }

    /** Score trending across all partitions */
    public TrendingResult scoreTrendingStream(TrendingQuery query) throws Exception {
        return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new TrendingExecuteQueryCallableFactory(new TrendingExecuteQuery<>(bitmaps, filterUtils, query)),
            new TrendingResultEvaluator(query),
            new MergeTrendingResults(query.desiredNumberOfDistincts),
            TrendingResult.EMPTY_RESULTS);
    }

    public TrendingResult scoreTrendingStream(MiruPartitionId partitionId, TrendingQuery query, Optional<TrendingResult> lastResult) throws Exception {
        return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new TrendingExecuteQueryCallableFactory(new TrendingExecuteQuery<>(bitmaps, filterUtils, query)),
            lastResult, TrendingResult.EMPTY_RESULTS);
    }

     public RecoResult collaborativeFilteringRecommendations(RecoQuery query) throws Exception {
         return callAndMerge(partitionDirector.allQueryablePartitionsInOrder(query.tenantId),
            new RecoExecuteQueryCallableFactory(new RecoExecuteQuery<>(bitmaps, filterUtils, query)),
            new RecoResultEvaluator(query),
            new MergeRecoResults(query.resultCount),
            RecoResult.EMPTY_RESULTS);
     }

     public RecoResult collaborativeFilteringRecommendations(MiruPartitionId partitionId, RecoQuery query, Optional<RecoResult> lastResult)
            throws Exception {
         return callImmediate(getLocalTenantPartition(query.tenantId, partitionId),
            new RecoExecuteQueryCallableFactory(new RecoExecuteQuery<>(bitmaps, filterUtils, query)),
            lastResult, RecoResult.EMPTY_RESULTS);
     }

    /** Proactively warm a tenant for immediate use. */
    public void warm(MiruTenantId tenantId) throws Exception {
        partitionDirector.warm(tenantId);
    }

    /** Manage topology and configuration. */
    public void setStorage(MiruTenantId tenantId, MiruPartitionId partitionId, MiruBackingStorage storage) throws Exception {
        partitionDirector.setStorage(tenantId, partitionId, storage);
    }

    public void removeHost(MiruHost host) throws Exception {
        partitionDirector.removeHost(host);
    }

    public void removeReplicas(MiruTenantId tenantId, MiruPartitionId partitionId) throws Exception {
        partitionDirector.removeReplicas(tenantId, partitionId);
    }

    public void moveReplica(MiruTenantId tenantId, MiruPartitionId partitionId, Optional<MiruHost> fromHost) throws Exception {
        partitionDirector.moveReplica(tenantId, partitionId, fromHost, localhost);
    }

    public void removeTopology(MiruTenantId tenantId, MiruPartitionId partitionId, MiruHost host) throws Exception {
        partitionDirector.removeTopology(tenantId, partitionId, host);
    }

    private Optional<MiruHostedPartition> getLocalTenantPartition(MiruTenantId tenantId, MiruPartitionId partitionId) {
        MiruPartitionCoord localPartitionCoord = new MiruPartitionCoord(tenantId, partitionId, localhost);
        return partitionDirector.getQueryablePartition(localPartitionCoord);
    }

    private <M extends MiruHostedPartition, R> R callAndMerge(Iterable<OrderedPartitions<M>> partitionReplicas,
        final ExecuteQueryCallableFactory<M, R> executeQueryCallableFactory,
        MiruResultEvaluator<R> evaluator,
        MiruResultMerger<R> merger,
        R defaultValue) throws InterruptedException {

        Optional<R> lastResult = Optional.absent();
        int numSearchedPartitions = 0;
        List<MiruSolution<R>> solutions = Lists.newArrayList();
        for (OrderedPartitions<M> orderedPartitions : partitionReplicas) {

            final Optional<R> result = lastResult;
            Collection<MiruSolvable<R>> solvables = Collections2.transform(orderedPartitions.partitions, new Function<M, MiruSolvable<R>>() {

                @Override
                public MiruSolvable<R> apply(final M replica) {
                    return executeQueryCallableFactory.create(replica, result);
                }
            });

            Optional<Long> suggestedTimeoutInMillis = partitionComparison.suggestTimeout(orderedPartitions.tenantId, orderedPartitions.partitionId,
                executeQueryCallableFactory.getExecuteQuery().getClass().getCanonicalName());
            MiruSolution<R> solution = solver.solve(solvables.iterator(), suggestedTimeoutInMillis);

            numSearchedPartitions++;

            if (solution == null) {
                // fatal timeout
                //TODO annotate result to indicate partial failure
                break;
            }

            solutions.add(solution);

            R currentResult = solution.getResult();
            R merged = merger.merge(lastResult, currentResult);

            lastResult = Optional.of(merged);
            if (evaluator.isDone(merged)) {
                break;
            }
        }

        debugPath(solutions);
        partitionComparison.analyzeSolutions(solutions);

        return merger.done(lastResult, defaultValue);
    }

    private <R> void debugPath(List<MiruSolution<R>> solutions) {
        if (log.isDebugEnabled()) {
            StringBuilder buf = new StringBuilder();
            int i = 0;
            for (MiruSolution<R> solution : solutions) {
                buf.append("\n  ").append(++i).append(". ").append(solution.getCoord()).append(" = ").append(solution.getResult());
            }

            log.debug("Partition path from {}:{}", localhost, buf);
        }
    }

    private <M extends MiruHostedPartition, R> R callImmediate(
        Optional<M> partition,
        ExecuteQueryCallableFactory<M, R> factory,
        Optional<R> lastResult, R defaultValue) throws Exception {

        if (partition.isPresent()) {
            Callable<R> callable = factory.create(partition.get(), lastResult);
            return callable.call();
        } else {
            return defaultValue;
        }
    }

    public boolean checkInfo(MiruTenantId tenantId, MiruPartitionId partitionId, MiruPartitionCoordInfo info) {
        return partitionDirector.checkInfo(tenantId, partitionId, info);
    }

}
