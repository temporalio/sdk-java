package io.temporal.payload.context;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@Experimental
public class ActivitySerializationContext implements HasWorkflowSerializationContext {
  private final @Nonnull String namespace;
  private final @Nullable String workflowId;
  private final @Nullable String workflowType;
  private final @Nullable String activityType;
  private final @Nullable String activityTaskQueue;
  private final boolean local;

  /**
   * @param namespace the activity's namespace; must not be {@code null}
   * @param workflowId the workflow ID that scheduled the activity, or {@code null} for standalone
   *     activities
   * @param workflowType the workflow type that scheduled the activity, or {@code null} for
   *     standalone activities
   * @param activityType the activity type name, or {@code null} if unknown. Activity type is
   *     unknown when getting a Standalone Activity result.
   * @param activityTaskQueue the task queue for this activity, or {@code null} if unknown. Task
   *     queue is unknown when getting a Standalone Activity result.
   * @param local {@code true} if this is a local activity
   */
  public ActivitySerializationContext(
      @Nonnull String namespace,
      @Nullable String workflowId,
      @Nullable String workflowType,
      @Nullable String activityType,
      @Nullable String activityTaskQueue,
      boolean local) {
    this.namespace = Objects.requireNonNull(namespace);
    this.workflowId = workflowId;
    this.workflowType = workflowType;
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
  @Nullable
  public String getWorkflowId() {
    return workflowId;
  }

  @Nullable
  public String getWorkflowType() {
    return workflowType;
  }

  @Nullable
  public String getActivityType() {
    return activityType;
  }

  @Nullable
  public String getActivityTaskQueue() {
    return activityTaskQueue;
  }

  public boolean isLocal() {
    return local;
  }
}
