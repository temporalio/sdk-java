package io.temporal.payload.context;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Experimental
public class ActivitySerializationContext implements HasWorkflowSerializationContext {
  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;
  private final @Nonnull String workflowType;
  private final @Nonnull String activityType;
  private final @Nonnull String activityTaskQueue;
  private final boolean local;

  /**
   * @param namespace the activity's namespace; must not be {@code null}
   * @param workflowId the workflow ID that scheduled the activity, or {@code null} for standalone
   *     activities (stored as an empty string)
   * @param workflowType the workflow type that scheduled the activity, or {@code null} for
   *     standalone activities (stored as an empty string)
   * @param activityType the activity type name; must not be {@code null}
   * @param activityTaskQueue the task queue for this activity; must not be {@code null}
   * @param local {@code true} if this is a local activity
   */
  public ActivitySerializationContext(
      @Nonnull String namespace,
      @Nullable String workflowId,
      @Nullable String workflowType,
      @Nonnull String activityType,
      @Nonnull String activityTaskQueue,
      boolean local) {
    this.namespace = Objects.requireNonNull(namespace);
    this.workflowId = workflowId != null ? workflowId : "";
    this.workflowType = workflowType != null ? workflowType : "";
    this.activityType = Objects.requireNonNull(activityType);
    this.activityTaskQueue = Objects.requireNonNull(activityTaskQueue);
    this.local = local;
  }

  public ActivitySerializationContext(ActivityInfo info) {
    this(
        info.getNamespace(),
        info.getWorkflowId(),
        info.getWorkflowType(),
        info.getActivityType(),
        info.getActivityTaskQueue(),
        info.isLocal());
  }

  @Override
  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  @Override
  @Nonnull
  public String getWorkflowId() {
    return workflowId;
  }

  @Nonnull
  public String getWorkflowType() {
    return workflowType;
  }

  @Nonnull
  public String getActivityType() {
    return activityType;
  }

  @Nonnull
  public String getActivityTaskQueue() {
    return activityTaskQueue;
  }

  public boolean isLocal() {
    return local;
  }
}
