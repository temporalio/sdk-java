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
     * A fairness key is a short string used for balancing task dispatch. Tasks with the same
     * fairness key will be processed proportionally according to their fairness weight.
     *
     * <p>If not set, inherits from the parent workflow or uses an empty string if there is no
     * parent.
     */
    public Builder setFairnessKey(String fairnessKey) {
      this.fairnessKey = fairnessKey;
      return this;
    }

    /**
     * A fairness weight determines the relative proportion of task processing for a given fairness
     * key. The weight should be a positive number. A higher weight means more tasks will be
     * processed for that fairness key.
     *
     * <p>If not set or 0, defaults to 1.0. If there is a parent workflow, inherits from the parent.
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
