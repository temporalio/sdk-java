package io.temporal.payload.context;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

@Experimental
public class WorkflowSerializationContext implements HasWorkflowSerializationContext {
  private final @Nonnull String namespace;
  private final @Nonnull String workflowId;

  // We can't currently reliably and consistency provide workflowType to the DataConverter.
  // 1. Signals and queries don't know workflowType when they are sent.
  // 2. WorkflowStub#getResult call is not aware of the workflowType, workflowType is an optional
  // parameter for a workflow stub that is not used to start a workflow.
  // private final @Nullable String workflowTypeName;

  public WorkflowSerializationContext(@Nonnull String namespace, @Nonnull String workflowId) {
    this.namespace = namespace;
    this.workflowId = workflowId;
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
}
