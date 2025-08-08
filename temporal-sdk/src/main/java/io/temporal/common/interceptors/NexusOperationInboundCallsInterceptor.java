package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationStillRunningException;
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

  final class FetchOperationResultInput {
    private final OperationContext operationContext;
    private final OperationFetchResultDetails operationFetchResultDetails;

    public FetchOperationResultInput(
        OperationContext operationContext,
        OperationFetchResultDetails operationFetchResultDetails) {
      this.operationContext = operationContext;
      this.operationFetchResultDetails = operationFetchResultDetails;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationFetchResultDetails getOperationFetchResultDetails() {
      return operationFetchResultDetails;
    }
  }

  final class FetchOperationResultOutput {
    private final Object result;

    public FetchOperationResultOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  final class FetchOperationInfoResponse {
    private final OperationInfo operationInfo;

    public FetchOperationInfoResponse(OperationInfo operationInfo) {
      this.operationInfo = operationInfo;
    }

    public OperationInfo getOperationInfo() {
      return operationInfo;
    }
  }

  final class FetchOperationInfoInput {
    private final OperationContext operationContext;
    private final OperationFetchInfoDetails operationFetchInfoDetails;

    public FetchOperationInfoInput(
        OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails) {
      this.operationContext = operationContext;
      this.operationFetchInfoDetails = operationFetchInfoDetails;
    }

    public OperationContext getOperationContext() {
      return operationContext;
    }

    public OperationFetchInfoDetails getOperationFetchInfoDetails() {
      return operationFetchInfoDetails;
    }
  }

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
   * Intercepts a call to fetch a Nexus operation result.
   *
   * @param input input to the operation result retrieval.
   * @throws OperationStillRunningException if the operation is still running.
   * @throws OperationException if the operation failed.
   * @return result of the operation.
   */
  FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationStillRunningException, OperationException;

  /**
   * Intercepts a call to fetch information about a Nexus operation.
   *
   * @param input input to the operation info retrieval.
   * @return information about the operation.
   */
  FetchOperationInfoResponse fetchOperationInfo(FetchOperationInfoInput input);

  /**
   * Intercepts a call to cancel a Nexus operation.
   *
   * @param input input to the operation cancel.
   * @return result of the operation cancel.
   */
  CancelOperationOutput cancelOperation(CancelOperationInput input);
}
