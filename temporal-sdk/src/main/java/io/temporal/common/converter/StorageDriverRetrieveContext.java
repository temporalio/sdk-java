package io.temporal.common.converter;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Context passed to {@link StorageDriver#retrieve} providing identity information about the
 * retrieval environment. This may be used by drivers for logging, metrics, or access control.
 */
@Experimental
public final class StorageDriverRetrieveContext {

  private final @Nullable String namespace;
  private final @Nullable String workflowId;

  private StorageDriverRetrieveContext(@Nullable String namespace, @Nullable String workflowId) {
    this.namespace = namespace;
    this.workflowId = workflowId;
  }

  /**
   * Creates a retrieve context with available identity information.
   *
   * @param namespace the namespace, may be null if not available
   * @param workflowId the workflow execution ID, may be null if not available
   * @return a new retrieve context
   */
  public static StorageDriverRetrieveContext create(
      @Nullable String namespace, @Nullable String workflowId) {
    return new StorageDriverRetrieveContext(namespace, workflowId);
  }

  /** Returns the namespace, or null if not available in the retrieval context. */
  @Nullable
  public String getNamespace() {
    return namespace;
  }

  /** Returns the workflow execution ID, or null if not available in the retrieval context. */
  @Nullable
  public String getWorkflowId() {
    return workflowId;
  }

  @Override
  public String toString() {
    return "StorageDriverRetrieveContext{"
        + "namespace='"
        + namespace
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + '}';
  }
}
