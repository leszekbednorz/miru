package com.jivesoftware.os.miru.stream.plugins.filter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.jivesoftware.os.miru.api.query.filter.MiruFilter;
import java.util.Arrays;

/**
 *
 * @author jonathan.colt
 */
public class AggregateCountsQueryConstraint {

    public final MiruFilter constraintsFilter;
    public final String aggregateCountAroundField;
    public final int startFromDistinctN;
    public final int desiredNumberOfDistincts;
    public final String[] gatherTermsForFields;

    public AggregateCountsQueryConstraint(
        @JsonProperty("constraintsFilter") MiruFilter constraintsFilter,
        @JsonProperty("aggregateCountAroundField") String aggregateCountAroundField,
        @JsonProperty("startFromDistinctN") int startFromDistinctN,
        @JsonProperty("desiredNumberOfDistincts") int desiredNumberOfDistincts,
        @JsonProperty("gatherTermsForFields") String[] gatherTermsForFields) {
        this.constraintsFilter = Preconditions.checkNotNull(constraintsFilter);
        this.aggregateCountAroundField = Preconditions.checkNotNull(aggregateCountAroundField);
        Preconditions.checkArgument(startFromDistinctN >= 0, "Start from distinct must be at least 0");
        this.startFromDistinctN = startFromDistinctN;
        Preconditions.checkArgument(desiredNumberOfDistincts > 0, "Number of distincts must be at least 1");
        this.desiredNumberOfDistincts = desiredNumberOfDistincts;
        this.gatherTermsForFields = gatherTermsForFields;
    }

    @Override
    public String toString() {
        return "AggregateCountsQueryConstraint{" +
            "constraintsFilter=" + constraintsFilter +
            ", aggregateCountAroundField='" + aggregateCountAroundField + '\'' +
            ", startFromDistinctN=" + startFromDistinctN +
            ", desiredNumberOfDistincts=" + desiredNumberOfDistincts +
            ", gatherTermsForFields=" + Arrays.toString(gatherTermsForFields) +
            '}';
    }
}
