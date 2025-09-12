package io.temporal.internal.nexus;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.handler.*;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;

public class TemporalInterceptorMiddleware implements OperationMiddleware {
  private final WorkerInterceptor[] interceptors;
  RootNexusOperationInboundCallsInterceptor rootInboundCallsInterceptor;

  public TemporalInterceptorMiddleware(WorkerInterceptor[] interceptors) {
    this.interceptors = interceptors;
  }

  @Override
  public OperationHandler<Object, Object> intercept(
      OperationContext context, OperationHandler<Object, Object> operationHandler) {
    rootInboundCallsInterceptor = new RootNexusOperationInboundCallsInterceptor(operationHandler);
    NexusOperationInboundCallsInterceptor inboundCallsInterceptor = rootInboundCallsInterceptor;
    for (WorkerInterceptor interceptor : interceptors) {
      inboundCallsInterceptor =
          interceptor.interceptNexusOperation(context, inboundCallsInterceptor);
    }

    InternalNexusOperationContext temporalNexusContext = CurrentNexusOperationContext.get();
    inboundCallsInterceptor.init(
        new RootNexusOperationOutboundCallsInterceptor(
            temporalNexusContext.getMetricsScope(),
            temporalNexusContext.getWorkflowClient(),
            new NexusInfoImpl(
                temporalNexusContext.getNamespace(), temporalNexusContext.getTaskQueue())));
    return new OperationInterceptorConverter(inboundCallsInterceptor);
  }

  static class OperationInterceptorConverter implements OperationHandler<Object, Object> {
    private final NexusOperationInboundCallsInterceptor next;

    public OperationInterceptorConverter(NexusOperationInboundCallsInterceptor next) {
      this.next = next;
    }

    @Override
    public OperationStartResult<Object> start(
        OperationContext operationContext, OperationStartDetails operationStartDetails, Object o)
        throws OperationException {
      return next.startOperation(
              new NexusOperationInboundCallsInterceptor.StartOperationInput(
                  operationContext, operationStartDetails, o))
          .getResult();
    }

    @Override
    public Object fetchResult(
        OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails)
        throws OperationException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public OperationInfo fetchInfo(
        OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails)
        throws HandlerException {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void cancel(
        OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
      next.cancelOperation(
          new NexusOperationInboundCallsInterceptor.CancelOperationInput(
              operationContext, operationCancelDetails));
    }
  }
}
