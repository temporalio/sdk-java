package io.temporal.internal.nexus;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationStartResult;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;

public class RootNexusOperationInboundCallsInterceptor
    implements NexusOperationInboundCallsInterceptor {
  private final OperationHandler<Object, Object> rootHandler;

  RootNexusOperationInboundCallsInterceptor(OperationHandler<Object, Object> rootHandler) {
    this.rootHandler = rootHandler;
  }

  @Override
  public void init(NexusOperationOutboundCallsInterceptor outboundCalls) {
    CurrentNexusOperationContext.get().setOutboundInterceptor(outboundCalls);
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    OperationStartResult result =
        rootHandler.start(input.getOperationContext(), input.getStartDetails(), input.getInput());
    return new StartOperationOutput(result);
  }

  @Override
  public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationStillRunningException, OperationException {
    Object result =
        rootHandler.fetchResult(
            input.getOperationContext(), input.getOperationFetchResultDetails());
    return new FetchOperationResultOutput(result);
  }

  @Override
  public FetchOperationInfoResponse fetchOperationInfo(FetchOperationInfoInput input) {
    return new FetchOperationInfoResponse(
        rootHandler.fetchInfo(input.getOperationContext(), input.getOperationFetchInfoDetails()));
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    rootHandler.cancel(input.getOperationContext(), input.getCancelDetails());
    return new CancelOperationOutput();
  }
}
