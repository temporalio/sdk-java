package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.*;
import io.temporal.common.Experimental;

/**
 * Intercepts inbound calls to a Nexus operation on the worker side.
 *
 * <p>An instance should be created in {@link
 * WorkerInterceptor#interceptNexusOperation(OperationContext,
 * NexusOperationInboundCallsInterceptor)}.
 *
 * <p>Prefer extending {@link NexusOperationInboundCallsInterceptorBase} and overriding only the
 * methods you need instead of implementing this interface directly. {@link
 * NexusOperationInboundCallsInterceptorBase} provides correct default implementations to all the
 * methods of this interface.
 */
@Experimental
public interface NexusOperationInboundCallsInterceptor {
  final class StartOperationInput {
    private final OperationContext operationContext;
    private final OperationStartDetails startDetails;
    private final Object input;

    public StartOperationInput(
        OperationContext operationContext, OperationStartDetails startDetails, Object input) {
      this.operationContext = operationContext;
      this.startDetails = startDetails;
      this.input = input;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationStartDetails getStartDetails() {
      return startDetails;
    }

    public Object getInput() {
      return input;
    }
  }

  final class StartOperationOutput {
    private final OperationStartResult<Object> result;

    public StartOperationOutput(OperationStartResult<Object> result) {
      this.result = result;
    }

    public OperationStartResult<Object> getResult() {
      return result;
    }
  }

  final class CancelOperationInput {
    private final OperationContext operationContext;
    private final OperationCancelDetails cancelDetails;

    public CancelOperationInput(
        OperationContext operationContext, OperationCancelDetails cancelDetails) {
      this.operationContext = operationContext;
      this.cancelDetails = cancelDetails;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationCancelDetails getCancelDetails() {
      return cancelDetails;
    }
  }

  final class CancelOperationOutput {}

  void init(NexusOperationOutboundCallsInterceptor outboundCalls);

  /**
   * Intercepts a call to start a Nexus operation.
   *
   * @param input input to the operation start.
   * @return result of the operation start.
   * @throws io.nexusrpc.OperationException if the operation start failed.
   */
  StartOperationOutput startOperation(StartOperationInput input) throws OperationException;

  /**
   * Intercepts a call to cancel a Nexus operation.
   *
   * @param input input to the operation cancel.
   * @return result of the operation cancel.
   */
  CancelOperationOutput cancelOperation(CancelOperationInput input);
}
