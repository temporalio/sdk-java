package io.temporal.common;

import java.util.Objects;

/**
 * Priority contains metadata that controls the relative ordering of task processing when tasks are
 * backed up in a queue. The affected queues depend on the server version.
 *
 * <p>Priority is attached to workflows and activities. By default, activities and child workflows
 * inherit Priority from the workflow that created them, but may override fields when an activity is
 * started or modified.
 *
 * <p>For all fields, the field not present or equal to zero/empty string means to inherit the value
 * from the calling workflow, or if there is no calling workflow, then use the default value.
 */
@Experimental
public class Priority {
  public static Priority.Builder newBuilder() {
    return new Priority.Builder(null);
  }

  public static Priority getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final Priority DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = Priority.newBuilder().build();
  }

  public static final class Builder {
    private int priorityKey;
    private String fairnessKey;
    private float fairnessWeight;

    private Builder(Priority options) {
      if (options == null) {
        return;
      }
      this.priorityKey = options.getPriorityKey();
      this.fairnessKey = options.getFairnessKey();
      this.fairnessWeight = options.getFairnessWeight();
    }

    /**
     * A priority key is a positive integer from 1 to n, where smaller integers correspond to higher
     * priorities (tasks run sooner). In general, tasks in a queue should be processed in close to
     * priority order, although small deviations are possible.
     *
     * <p>The maximum priority value (minimum priority) is determined by server configuration, and
     * defaults to 5.
     *
     * <p>The default value when unset or 0 is calculated by (min+max)/2. With the default max of 5,
     * and min of 1, that comes out to 3.
     */
    public Builder setPriorityKey(int priorityKey) {
      this.priorityKey = priorityKey;
      return this;
    }

    /**
     * FairnessKey is a short string that's used as a key for a fairness balancing mechanism. It may
     * correspond to a tenant id, or to a fixed string like "high" or "low". The default is the
     * empty string.
     *
     * <p>>The fairness mechanism attempts to dispatch tasks for a given key in proportion to its
     * weight. For example, using a thousand distinct tenant ids, each with a weight of 1.0 (the
     * default) will result in each tenant getting a roughly equal share of task dispatch
     * throughput.
     *
     * <p>Fairness keys are limited to 64 bytes.
     */
    public Builder setFairnessKey(String fairnessKey) {
      this.fairnessKey = fairnessKey;
      return this;
    }

    /**
     * FairnessWeight for a task can come from multiple sources for flexibility. From highest to
     * lowest precedence:
     *
     * <ul>
     *   <li>Weights for a small set of keys can be overridden in task queue configuration with an
     *       API.
     *   <li>It can be attached to the workflow/activity in this field.
     *   <li>The default weight of 1.0 will be used.
     * </ul>
     *
     * <p>Weight values are clamped to the range [0.001, 1000].
     */
    public Builder setFairnessWeight(float fairnessWeight) {
      this.fairnessWeight = fairnessWeight;
      return this;
    }

    public Priority build() {
      return new Priority(priorityKey, fairnessKey, fairnessWeight);
    }
  }

  private Priority(int priorityKey, String fairnessKey, float fairnessWeight) {
    this.priorityKey = priorityKey;
    this.fairnessKey = fairnessKey;
    this.fairnessWeight = fairnessWeight;
  }

  private final int priorityKey;
  private final String fairnessKey;
  private final float fairnessWeight;

  /**
   * See {@link Builder#setPriorityKey(int)}
   *
   * @return The priority key
   */
  public int getPriorityKey() {
    return priorityKey;
  }

  /**
   * See {@link Builder#setFairnessKey(String)}
   *
   * @return The fairness key
   */
  public String getFairnessKey() {
    return fairnessKey;
  }

  /**
   * See {@link Builder#setFairnessWeight(float)}
   *
   * @return The fairness weight
   */
  public float getFairnessWeight() {
    return fairnessWeight;
  }

  @Override
  public String toString() {
    return "Priority{"
        + "priorityKey="
        + priorityKey
        + ", fairnessKey='"
        + fairnessKey
        + '\''
        + ", fairnessWeight="
        + fairnessWeight
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    Priority priority = (Priority) o;
    return priorityKey == priority.priorityKey
        && Float.compare(priority.fairnessWeight, fairnessWeight) == 0
        && Objects.equals(fairnessKey, priority.fairnessKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(priorityKey, fairnessKey, fairnessWeight);
  }
}
