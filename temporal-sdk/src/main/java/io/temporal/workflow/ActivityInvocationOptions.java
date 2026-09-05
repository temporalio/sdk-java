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

  /** Creates a builder with non-local Activity options that apply only to this invocation. */
  public static Builder newBuilder(ActivityOptions activityOptions) {
    return new Builder().setActivityOptions(activityOptions);
  }

  public static final class Builder {
    private String activityId;
    private ActivityOptions activityOptions;

    private Builder() {}

    private Builder(ActivityInvocationOptions options) {
      if (options != null) {
        this.activityId = options.activityId;
        this.activityOptions = options.activityOptions;
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
     * Sets Activity options to use instead of the reusable stub options for this invocation.
     *
     * <p>These options completely replace stub and method-specific options for this invocation.
     * They cannot be used with Local Activities.
     */
    public Builder setActivityOptions(ActivityOptions activityOptions) {
      this.activityOptions = Objects.requireNonNull(activityOptions, "activityOptions");
      return this;
    }

    public ActivityInvocationOptions build() {
      return new ActivityInvocationOptions(activityId, activityOptions);
    }
  }

  private final String activityId;
  private final ActivityOptions activityOptions;

  private ActivityInvocationOptions(String activityId, ActivityOptions activityOptions) {
    this.activityId = activityId;
    this.activityOptions = activityOptions;
  }

  /** Returns the caller-supplied Activity ID, or {@code null} if the SDK should generate one. */
  @Nullable
  public String getActivityId() {
    return activityId;
  }

  /**
   * Returns replacement options for this non-local Activity invocation, or {@code null} if none
   * were supplied.
   */
  @Nullable
  public ActivityOptions getActivityOptions() {
    return activityOptions;
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
        && Objects.equals(activityOptions, that.activityOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(activityId, activityOptions);
  }

  @Override
  public String toString() {
    return "ActivityInvocationOptions{"
        + "activityId='"
        + activityId
        + '\''
        + ", activityOptions="
        + activityOptions
        + '}';
  }
}
