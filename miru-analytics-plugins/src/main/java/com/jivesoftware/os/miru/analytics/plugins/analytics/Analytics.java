package com.jivesoftware.os.miru.analytics.plugins.analytics;

import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.miru.api.MiruPartitionCoord;
import com.jivesoftware.os.miru.api.query.filter.MiruAuthzExpression;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmaps;
import com.jivesoftware.os.miru.plugin.bitmap.MiruBitmapsDebug;
import com.jivesoftware.os.miru.plugin.context.MiruRequestContext;
import com.jivesoftware.os.miru.plugin.index.MiruTimeIndex;
import com.jivesoftware.os.miru.plugin.solution.MiruAggregateUtil;
import com.jivesoftware.os.miru.plugin.solution.MiruRequestHandle;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLog;
import com.jivesoftware.os.miru.plugin.solution.MiruSolutionLogLevel;
import com.jivesoftware.os.miru.plugin.solution.MiruTimeRange;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
public class Analytics {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final MiruAggregateUtil aggregateUtil = new MiruAggregateUtil();
    private final MiruBitmapsDebug bitmapsDebug = new MiruBitmapsDebug();

    public interface Analysis<T> {

        boolean consume(ToAnalyze<T> toAnalyze) throws Exception;
    }

    public interface ToAnalyze<T> {

        boolean analyze(T term, MiruFilter filter) throws Exception;
    }

    public interface Analyzed<T> {

        boolean analyzed(T term, long[] waveformBuffer) throws Exception;
    }

    public <BM extends IBM, IBM, T> boolean analyze(MiruSolutionLog solutionLog,
        MiruRequestHandle<BM, IBM, ?> handle,
        MiruRequestContext<IBM, ?> context,
        MiruAuthzExpression authzExpression,
        MiruTimeRange timeRange,
        MiruFilter constraintsFilter,
        int divideTimeRangeIntoNSegments,
        Analysis<T> analysis,
        Analyzed<T> analyzed) throws Exception {

        StackBuffer stackBuffer = new StackBuffer();

        MiruBitmaps<BM, IBM> bitmaps = handle.getBitmaps();
        MiruPartitionCoord coord = handle.getCoord();
        MiruTimeIndex timeIndex = context.getTimeIndex();

        // Short-circuit if this is not a properly bounded query
        if (timeRange.largestTimestamp == Long.MAX_VALUE || timeRange.smallestTimestamp == 0) {
            solutionLog.log(MiruSolutionLogLevel.WARN, "Improperly bounded query: {}", timeRange);
            analysis.consume((termId, filter) -> analyzed.analyzed(termId, null));
            return true;
        }

        // Short-circuit if the time range doesn't live here
        boolean resultsExhausted = timeRange.smallestTimestamp > timeIndex.getLargestTimestamp();
        if (!timeIndex.intersects(timeRange)) {
            solutionLog.log(MiruSolutionLogLevel.WARN, "No time index intersection. Partition {}: {} doesn't intersect with {}",
                coord.partitionId, timeIndex, timeRange);
            analysis.consume((termId, filter) -> analyzed.analyzed(termId, null));
            return resultsExhausted;
        }

        // Start building up list of bitmap operations to run
        List<IBM> ands = new ArrayList<>();

        long start = System.currentTimeMillis();
        ands.add(bitmaps.buildTimeRangeMask(timeIndex, timeRange.smallestTimestamp, timeRange.largestTimestamp, stackBuffer));
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics timeRangeMask: {} millis.", System.currentTimeMillis() - start);

        // 1) Execute the combined filter above on the given stream, add the bitmap
        if (MiruFilter.NO_FILTER.equals(constraintsFilter)) {
            solutionLog.log(MiruSolutionLogLevel.INFO, "analytics filter: no constraints.");
        } else {
            start = System.currentTimeMillis();
            BM filtered = aggregateUtil.filter(bitmaps, context.getSchema(), context.getTermComposer(), context.getFieldIndexProvider(), constraintsFilter,
                solutionLog, null, context.getActivityIndex().lastId(stackBuffer), -1, stackBuffer);
            solutionLog.log(MiruSolutionLogLevel.INFO, "analytics filter: {} millis.", System.currentTimeMillis() - start);
            ands.add(filtered);
        }

        // 2) Add in the authz check if we have it
        if (!MiruAuthzExpression.NOT_PROVIDED.equals(authzExpression)) {
            ands.add(context.getAuthzIndex().getCompositeAuthz(authzExpression, stackBuffer));
        }

        // 3) Mask out anything that hasn't made it into the activityIndex yet, or that has been removed from the index
        start = System.currentTimeMillis();
        ands.add(bitmaps.buildIndexMask(context.getActivityIndex().lastId(stackBuffer), context.getRemovalIndex().getIndex(stackBuffer)));
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics indexMask: {} millis.", System.currentTimeMillis() - start);

        // AND it all together to get the final constraints
        bitmapsDebug.debug(solutionLog, bitmaps, "ands", ands);
        start = System.currentTimeMillis();
        BM constrained = bitmaps.and(ands);
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics constrained: {} millis.", System.currentTimeMillis() - start);

        if (solutionLog.isLogLevelEnabled(MiruSolutionLogLevel.INFO)) {
            solutionLog.log(MiruSolutionLogLevel.INFO, "analytics constrained {} items.", bitmaps.cardinality(constrained));
        }

        long currentTime = timeRange.smallestTimestamp;
        long segmentDuration = (timeRange.largestTimestamp - timeRange.smallestTimestamp) / divideTimeRangeIntoNSegments;
        if (segmentDuration < 1) {
            throw new RuntimeException("Time range is insufficient to be divided into " + divideTimeRangeIntoNSegments + " segments");
        }

        start = System.currentTimeMillis();
        int[] indexes = new int[divideTimeRangeIntoNSegments + 1];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = Math.abs(timeIndex.getClosestId(currentTime, stackBuffer)); // handle negative "theoretical insertion" index
            currentTime += segmentDuration;
        }
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics bucket boundaries: {} millis.", System.currentTimeMillis() - start);

        start = System.currentTimeMillis();
        int[] count = new int[1];
        long[] rawWaveformBuffer = new long[divideTimeRangeIntoNSegments];

        analysis.consume((term, filter) -> {
            boolean found = false;
            if (!bitmaps.isEmpty(constrained)) {
                BM waveformFiltered = aggregateUtil.filter(bitmaps, context.getSchema(), context.getTermComposer(), context.getFieldIndexProvider(), filter,
                    solutionLog, null, context.getActivityIndex().lastId(stackBuffer), -1, stackBuffer);
                BM answer;
                if (bitmaps.supportsInPlace()) {
                    answer = waveformFiltered;
                    bitmaps.inPlaceAnd(waveformFiltered, constrained);
                } else {
                    answer = bitmaps.and(Arrays.asList(constrained, waveformFiltered));
                }
                if (!bitmaps.isEmpty(answer)) {
                    found = true;
                    Arrays.fill(rawWaveformBuffer, 0);
                    bitmaps.boundedCardinalities(answer, indexes, rawWaveformBuffer);

                    if (solutionLog.isLogLevelEnabled(MiruSolutionLogLevel.DEBUG)) {
                        solutionLog.log(MiruSolutionLogLevel.DEBUG, "analytics answer: {} items.", bitmaps.cardinality(answer));
                        solutionLog.log(MiruSolutionLogLevel.DEBUG, "analytics name: {}, waveform: {}.", term, Arrays.toString(rawWaveformBuffer));
                    }
                } else {
                    solutionLog.log(MiruSolutionLogLevel.DEBUG, "analytics empty answer.");
                }
            }
            count[0]++;
            return analyzed.analyzed(term, found ? rawWaveformBuffer : null);
        });
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics answered: {} millis.", System.currentTimeMillis() - start);
        solutionLog.log(MiruSolutionLogLevel.INFO, "analytics answered: {} iterations.", count[0]);

        return resultsExhausted;
    }

}
