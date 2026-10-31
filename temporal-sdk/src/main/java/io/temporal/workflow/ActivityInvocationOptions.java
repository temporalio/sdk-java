package io.temporal.workflow;

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

    private Builder() {}

    private Builder(ActivityInvocationOptions options) {
      if (options != null) {
        this.activityId = options.activityId;
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

    public ActivityInvocationOptions build() {
      return new ActivityInvocationOptions(activityId);
    }
  }

  private final String activityId;

  private ActivityInvocationOptions(String activityId) {
    this.activityId = activityId;
  }

  /** Returns the caller-supplied Activity ID, or {@code null} if the SDK should generate one. */
  @Nullable
  public String getActivityId() {
    return activityId;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ActivityInvocationOptions that = (ActivityInvocationOptions) o;
    return Objects.equals(activityId, that.activityId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(activityId);
  }

  @Override
  public String toString() {
    return "ActivityInvocationOptions{" + "activityId='" + activityId + '\'' + '}';
  }
}
