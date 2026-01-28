package io.temporal.payload.context;

import io.temporal.activity.ActivityInfo;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;

@Experimental
public class ActivitySerializationContext implements HasWorkflowSerializationContext {
  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;
  private final @Nonnull String workflowType;
  private final @Nonnull String activityType;
  private final @Nonnull String activityTaskQueue;
  private final boolean local;

  public ActivitySerializationContext(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nonnull String workflowType,
      @Nonnull String activityType,
      @Nonnull String activityTaskQueue,
      boolean local) {
    this.namespace = Objects.requireNonNull(namespace);
    this.workflowId = Objects.requireNonNull(workflowId);
    this.workflowType = Objects.requireNonNull(workflowType);
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
