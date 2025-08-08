package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.temporal.common.Experimental;

/** Convenience base class for {@link NexusOperationInboundCallsInterceptor} implementations. */
@Experimental
public class NexusOperationInboundCallsInterceptorBase
    implements NexusOperationInboundCallsInterceptor {
  private final NexusOperationInboundCallsInterceptor next;

  public NexusOperationInboundCallsInterceptorBase(NexusOperationInboundCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public void init(NexusOperationOutboundCallsInterceptor outboundCalls) {
    next.init(outboundCalls);
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    return next.startOperation(input);
  }

  @Override
  public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationStillRunningException, OperationException {
    return next.fetchOperationResult(input);
  }

  @Override
  public FetchOperationInfoResponse fetchOperationInfo(FetchOperationInfoInput input) {
    return next.fetchOperationInfo(input);
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    return next.cancelOperation(input);
  }
}
