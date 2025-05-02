package io.temporal.common.interceptors;

import io.nexusrpc.handler.OperationContext;

public class WorkerInterceptorBase implements WorkerInterceptor {
  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return next;
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return next;
  }

  @Override
  public NexusOperationInboundCallsInterceptor interceptNexusOperation(
      OperationContext context, NexusOperationInboundCallsInterceptor next) {
    return next;
  }
}
