package io.temporal.internal.nexus;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationStartResult;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;

public class RootNexusOperationInboundCallsInterceptor
    implements NexusOperationInboundCallsInterceptor {
  private final OperationHandler<Object, Object> operationInterceptor;

  RootNexusOperationInboundCallsInterceptor(OperationHandler<Object, Object> operationInterceptor) {
    this.operationInterceptor = operationInterceptor;
  }

  @Override
  public void init(NexusOperationOutboundCallsInterceptor outboundCalls) {
    CurrentNexusOperationContext.get().setOutboundInterceptor(outboundCalls);
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    OperationStartResult result =
        operationInterceptor.start(
            input.getOperationContext(), input.getStartDetails(), input.getInput());
    return new StartOperationOutput(result);
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    operationInterceptor.cancel(input.getOperationContext(), input.getCancelDetails());
    return new CancelOperationOutput();
  }
}
