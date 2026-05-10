package io.temporal.client;

import io.temporal.api.workflowservice.v1.CountActivityExecutionsResponse;
import io.temporal.common.Experimental;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.List;
import java.util.Objects;
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

    AggregationGroup(long count, List<io.temporal.api.common.v1.Payload> groupValues) {
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AggregationGroup that = (AggregationGroup) o;
      return count == that.count && Objects.equals(groupValues, that.groupValues);
    }

    @Override
    public int hashCode() {
      return Objects.hash(groupValues, count);
    }

    @Override
    public String toString() {
      return "AggregationGroup{groupValues=" + groupValues + ", count=" + count + '}';
    }
  }

  private final long count;
  private final List<AggregationGroup> groups;

  public ActivityExecutionCount(@Nonnull CountActivityExecutionsResponse response) {
    this.count = response.getCount();
    this.groups =
        response.getGroupsList().stream()
            .map(g -> new AggregationGroup(g.getCount(), g.getGroupValuesList()))
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityExecutionCount that = (ActivityExecutionCount) o;
    return count == that.count && Objects.equals(groups, that.groups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(count, groups);
  }

  @Override
  public String toString() {
    return "ActivityExecutionCount{count=" + count + ", groups=" + groups + '}';
  }
}
