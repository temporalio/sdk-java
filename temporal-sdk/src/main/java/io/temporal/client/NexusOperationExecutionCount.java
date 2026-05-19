package io.temporal.client;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/** Result of counting standalone Nexus operation executions. */
@Experimental
public class NexusOperationExecutionCount {

  /** An individual aggregation group. */
  @Experimental
  public static class AggregationGroup {
    private final List<List<?>> groupValues;
    private final long count;

    /** Construct from raw payload group values; values are decoded eagerly. */
    public AggregationGroup(long count, List<Payload> groupValues) {
      this.groupValues =
          groupValues.stream().map(SearchAttributesUtil::decode).collect(Collectors.toList());
      this.count = count;
    }

    /** Values of the group, decoded from search attribute payloads. */
    public List<List<?>> getGroupValues() {
      return groupValues;
    }

    /** Count of operations in this group. */
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

  public NexusOperationExecutionCount(long count, List<AggregationGroup> groups) {
    this.count = count;
    this.groups = Collections.unmodifiableList(groups);
  }

  /** Total number of operation executions matching the query. */
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
    NexusOperationExecutionCount that = (NexusOperationExecutionCount) o;
    return count == that.count && Objects.equals(groups, that.groups);
  }

  @Override
  public int hashCode() {
    return Objects.hash(count, groups);
  }

  @Override
  public String toString() {
    return "NexusOperationExecutionCount{count=" + count + ", groups=" + groups + '}';
  }
}
