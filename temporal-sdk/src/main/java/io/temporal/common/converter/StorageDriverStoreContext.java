package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Context passed to {@link StorageDriver#store} providing identity information about the workflow
 * or activity that owns the payload being stored. Drivers can use this context to organize stored
 * objects (e.g., by namespace and workflow ID in the object key path).
 *
 * <p>Instances of this class are immutable and therefore thread-safe.
 */
@Experimental
public final class StorageDriverStoreContext {

  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;
  private final @Nullable String runId;
  private final @Nullable String activityType;

  private StorageDriverStoreContext(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nullable String runId,
      @Nullable String activityType) {
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
    this.runId = runId;
    this.activityType = activityType;
  }

  /**
   * Creates a store context for a workflow-level payload.
   *
   * @param namespace the namespace the workflow belongs to
   * @param workflowId the workflow execution ID
   * @param runId the workflow run ID, may be null
   * @return a new store context
   */
  public static StorageDriverStoreContext forWorkflow(
      @Nonnull String namespace, @Nonnull String workflowId, @Nullable String runId) {
    return new StorageDriverStoreContext(namespace, workflowId, runId, null);
  }

  /**
   * Creates a store context for an activity-level payload.
   *
   * @param namespace the namespace the workflow belongs to
   * @param workflowId the workflow execution ID
   * @param runId the workflow run ID, may be null
   * @param activityType the activity type name
   * @return a new store context
   */
  public static StorageDriverStoreContext forActivity(
      @Nonnull String namespace,
      @Nonnull String workflowId,
      @Nullable String runId,
      @Nonnull String activityType) {
    return new StorageDriverStoreContext(namespace, workflowId, runId, activityType);
  }

  /** Returns the namespace the workflow execution belongs to. */
  @Nonnull
  public String getNamespace() {
    return namespace;
  }

  /** Returns the workflow execution ID. */
  @Nonnull
  public String getWorkflowId() {
    return workflowId;
  }

  /** Returns the workflow run ID, or null if not available. */
  @Nullable
  public String getRunId() {
    return runId;
  }

  /** Returns the activity type name, or null if this is a workflow-level context. */
  @Nullable
  public String getActivityType() {
    return activityType;
  }

  /** Returns true if this context is for an activity payload (vs. a workflow payload). */
  public boolean isActivityContext() {
    return activityType != null;
  }

  @Override
  public String toString() {
    return "StorageDriverStoreContext{"
        + "namespace='"
        + namespace
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + ", runId='"
        + runId
        + '\''
        + ", activityType='"
        + activityType
        + '\''
        + '}';
  }
}
