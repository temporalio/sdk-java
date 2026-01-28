package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;

/**
 * Interface that stub created through {@link
 * io.temporal.workflow.Workflow#newChildWorkflowStub(Class)} implements. Do not implement or use
 * this interface in any application code. Use {@link
 * io.temporal.workflow.Workflow#getWorkflowExecution(Object)} to access {@link WorkflowExecution}
 * out of a workflow stub.
 */
public interface StubMarker {
  String GET_UNTYPED_STUB_METHOD = "__getUntypedStub";

  Object __getUntypedStub();
}
