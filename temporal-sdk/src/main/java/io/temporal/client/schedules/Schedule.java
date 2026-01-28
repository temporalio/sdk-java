package io.temporal.client.schedules;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** A schedule for periodically running an action. */
public final class Schedule {
  public static Schedule.Builder newBuilder() {
    return new Schedule.Builder();
  }

  public static Schedule.Builder newBuilder(Schedule options) {
    return new Schedule.Builder(options);
  }

  public static final class Builder {
    private ScheduleAction action;
    private ScheduleSpec spec;
    private SchedulePolicy policy;
    private ScheduleState state;

    private Builder() {}

    private Builder(Schedule options) {
      if (options == null) {
        return;
      }
      action = options.action;
      policy = options.policy;
      state = options.state;
      spec = options.spec;
    }

    /**
     * Set the action for this schedule. Required to build.
     *
     * @see ScheduleAction
     */
    public Builder setAction(ScheduleAction action) {
      this.action = action;
      return this;
    }

    /**
     * Set the spec for this schedule. Required to build.
     *
     * @see ScheduleSpec
     */
    public Builder setSpec(ScheduleSpec spec) {
      this.spec = spec;
      return this;
    }

    /**
     * Set the policy for this schedule
     *
     * @see SchedulePolicy
     */
    public Builder setPolicy(SchedulePolicy policy) {
      this.policy = policy;
      return this;
    }

    /**
     * Set the state for this schedule
     *
     * @see ScheduleState
     */
    public Builder setState(ScheduleState state) {
      this.state = state;
      return this;
    }

    public Schedule build() {
      return new Schedule(
          Objects.requireNonNull(action), Objects.requireNonNull(spec), policy, state);
    }
  }

  private final ScheduleAction action;
  private final SchedulePolicy policy;
  private final ScheduleState state;
  private final ScheduleSpec spec;

  private Schedule(
      ScheduleAction action, ScheduleSpec spec, SchedulePolicy policy, ScheduleState state) {
    this.action = action;
    this.spec = spec;
    this.policy = policy;
    this.state = state;
  }

  /**
   * Gets the action for the schedule.
   *
   * @return action of the schedule
   */
  @Nonnull
  public ScheduleAction getAction() {
    return action;
  }

  /**
   * Gets the spec for the schedule.
   *
   * @return spec of the schedule
   */
  @Nonnull
  public ScheduleSpec getSpec() {
    return spec;
  }

  /**
   * Gets the policy for the schedule.
   *
   * @return policy of the schedule
   */
  @Nullable
  public SchedulePolicy getPolicy() {
    return policy;
  }

  /**
   * Gets the state of the schedule.
   *
   * @return state of the schedule
   */
  @Nullable
  public ScheduleState getState() {
    return state;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Schedule schedule = (Schedule) o;
    return Objects.equals(action, schedule.action)
        && Objects.equals(policy, schedule.policy)
        && Objects.equals(state, schedule.state)
        && Objects.equals(spec, schedule.spec);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, policy, state, spec);
  }

  @Override
  public String toString() {
    return "Schedule{"
        + "action="
        + action
        + ", policy="
        + policy
        + ", state="
        + state
        + ", spec="
        + spec
        + '}';
  }
}
