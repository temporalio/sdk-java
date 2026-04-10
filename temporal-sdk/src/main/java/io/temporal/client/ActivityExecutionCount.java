package io.temporal.client;

import io.temporal.api.workflowservice.v1.CountActivityExecutionsResponse;
import io.temporal.common.Experimental;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/** Result of counting standalone activity executions. */
@Experimental
public class ActivityExecutionCount {

  /** An individual aggregation group. */
  @Experimental
  public static class AggregationGroup {
    private final List<List<?>> groupValues;
    private final long count;

    AggregationGroup(List<io.temporal.api.common.v1.Payload> groupValues, long count) {
      this.groupValues =
          groupValues.stream().map(SearchAttributesUtil::decode).collect(Collectors.toList());
      this.count = count;
    }

    /** Values of the group, decoded from search attribute payloads. */
    public List<List<?>> getGroupValues() {
      return groupValues;
    }

    /** Count of activities in this group. */
    public long getCount() {
      return count;
    }
  }

  private final long count;
  private final List<AggregationGroup> groups;

  public ActivityExecutionCount(@Nonnull CountActivityExecutionsResponse response) {
    this.count = response.getCount();
    this.groups =
        response.getGroupsList().stream()
            .map(g -> new AggregationGroup(g.getGroupValuesList(), g.getCount()))
            .collect(Collectors.toList());
  }

  /** Total number of activity executions matching the query. */
  public long getCount() {
    return count;
  }

  /** Aggregation groups returned by the service. Empty if no grouping was requested. */
  @Nonnull
  public List<AggregationGroup> getGroups() {
    return groups;
  }
}
