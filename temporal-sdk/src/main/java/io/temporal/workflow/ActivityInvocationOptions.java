package io.temporal.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nullable;

/** Options that apply to a single Workflow Activity or Local Activity invocation. */
@Experimental
public final class ActivityInvocationOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ActivityInvocationOptions options) {
    return new Builder(options);
  }

  public static ActivityInvocationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ActivityInvocationOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ActivityInvocationOptions.newBuilder().build();
  }

  public static final class Builder {
    private String activityId;
    private Functions.Proc1<ActivityOptions.Builder> activityOptionsOverride;

    private Builder() {}

    private Builder(ActivityInvocationOptions options) {
      if (options != null) {
        this.activityId = options.activityId;
        this.activityOptionsOverride = options.activityOptionsOverride;
      }
    }

    /**
     * Sets the identifier for this Activity or Local Activity invocation.
     *
     * <p>The identifier must be unique among open Activity Executions within the current Workflow
     * Run. If it is not set, the SDK generates an identifier.
     */
    public Builder setActivityId(String activityId) {
      Objects.requireNonNull(activityId, "activityId");
      if (activityId.isEmpty()) {
        throw new IllegalArgumentException("activityId must not be empty");
      }
      this.activityId = activityId;
      return this;
    }

    /**
     * Customizes the options for this Activity invocation without changing the stub's options.
     *
     * <pre>{@code
     * ActivityInvocationOptions options = ActivityInvocationOptions.newBuilder()
     *     .setActivityOptionsOverride(b -> b.setStartToCloseTimeout(Duration.ofMinutes(2)))
     *     .build();
     * Workflow.executeActivity(activities::process, options, input);
     * }</pre>
     *
     * <p>The callback receives a fresh builder containing the resolved stub options, including
     * activity method options and retry annotations for typed stubs. It runs last, so settings not
     * modified by the callback are retained. It can explicitly set boolean options to false or
     * clear optional values with null. The resulting options must include a StartToCloseTimeout or
     * ScheduleToCloseTimeout.
     *
     * <p>Setting retry options replaces the entire retry configuration, including values from retry
     * annotations; individual retry fields are not merged. Setting retry options to null uses the
     * server-defined default retry policy.
     *
     * <p>Supported for typed and untyped Activities, both synchronous and asynchronous. Local
     * Activities do not support this customization and reject invocation options containing it.
     *
     * <p>The callback runs in workflow code when each invocation is scheduled, including during
     * replay, not when these invocation options are built. It must be deterministic, should only
     * modify the supplied builder, and must not retain it.
     *
     * @throws NullPointerException if {@code activityOptionsOverride} is null.
     */
    public Builder setActivityOptionsOverride(
        Functions.Proc1<ActivityOptions.Builder> activityOptionsOverride) {
      this.activityOptionsOverride =
          Objects.requireNonNull(activityOptionsOverride, "activityOptionsOverride");
      return this;
    }

    public ActivityInvocationOptions build() {
      return new ActivityInvocationOptions(activityId, activityOptionsOverride);
    }
  }

  private final String activityId;
  private final Functions.Proc1<ActivityOptions.Builder> activityOptionsOverride;

  private ActivityInvocationOptions(
      String activityId, Functions.Proc1<ActivityOptions.Builder> activityOptionsOverride) {
    this.activityId = activityId;
    this.activityOptionsOverride = activityOptionsOverride;
  }

  /** Returns the caller-supplied Activity ID, or {@code null} if the SDK should generate one. */
  @Nullable
  public String getActivityId() {
    return activityId;
  }

  /** Returns the Activity options customization, or {@code null} to use the stub's options. */
  @Nullable
  public Functions.Proc1<ActivityOptions.Builder> getActivityOptionsOverride() {
    return activityOptionsOverride;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityInvocationOptions that = (ActivityInvocationOptions) o;
    return Objects.equals(activityId, that.activityId)
        && Objects.equals(activityOptionsOverride, that.activityOptionsOverride);
  }

  @Override
  public int hashCode() {
    return Objects.hash(activityId, activityOptionsOverride);
  }

  @Override
  public String toString() {
    return "ActivityInvocationOptions{"
        + "activityId='"
        + activityId
        + '\''
        + ", activityOptionsOverride="
        + activityOptionsOverride
        + '}';
  }
}
