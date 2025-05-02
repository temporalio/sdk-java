package io.temporal.nexus;

import static io.temporal.internal.common.InternalUtils.createNexusBoundStub;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.client.NexusStartWorkflowRequest;

class WorkflowStubHandleInvoker implements WorkflowHandleInvoker {
  final Object[] args;
  final WorkflowStub stub;

  WorkflowStubHandleInvoker(WorkflowStub stub, Object[] args) {
    this.args = args;
    this.stub = stub;
  }

  @Override
  public WorkflowExecution invoke(NexusStartWorkflowRequest request) {
    return createNexusBoundStub(stub, request).start(args);
  }
}
