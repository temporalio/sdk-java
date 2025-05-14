package io.temporal.client;

import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsResponse;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/** Result of counting workflow executions. */
public class WorkflowExecutionCount {
  /** Individual aggregation group record. */
  public static class AggregationGroup {
    private final List<List<?>> groupValues;
    private final long count;

    AggregationGroup(List<io.temporal.api.common.v1.Payload> groupValues, long count) {
      this.groupValues =
          groupValues.stream().map(SearchAttributesUtil::decode).collect(Collectors.toList());
      this.count = count;
    }

    /** Values of the group. */
    public List<List<?>> getGroupValues() {
      return groupValues;
    }

    /** Count of workflows in the group. */
    public long getCount() {
      return count;
    }
  }

  private final long count;
  private final List<AggregationGroup> groups;

  public WorkflowExecutionCount(@Nonnull CountWorkflowExecutionsResponse response) {
    this.count = response.getCount();
    this.groups =
        response.getGroupsList().stream()
            .map(g -> new AggregationGroup(g.getGroupValuesList(), g.getCount()))
            .collect(Collectors.toList());
  }

  /** Total number of workflows matching the request. */
  public long getCount() {
    return count;
  }

  /** Aggregation groups returned by the service. */
  public List<AggregationGroup> getGroups() {
    return groups;
  }
}
