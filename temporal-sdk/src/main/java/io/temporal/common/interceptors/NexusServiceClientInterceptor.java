package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.transport.CancelOperationOptions;
import io.nexusrpc.client.transport.CancelOperationResponse;
import io.nexusrpc.client.transport.CompleteOperationOptions;
import io.nexusrpc.client.transport.CompleteOperationResponse;
import io.nexusrpc.client.transport.FetchOperationInfoOptions;
import io.nexusrpc.client.transport.FetchOperationInfoResponse;
import io.nexusrpc.client.transport.FetchOperationResultOptions;
import io.nexusrpc.client.transport.FetchOperationResultResponse;
import io.nexusrpc.client.transport.StartOperationOptions;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts calls made by a {@link io.nexusrpc.client.ServiceClient}.
 *
 * <p>Prefer extending {@link NexusServiceClientInterceptorBase} and overriding only the methods you
 * need instead of implementing this interface directly. {@link NexusServiceClientInterceptorBase}
 * provides correct default implementations to all the methods of this interface.
 */
public interface NexusServiceClientInterceptor {

  /**
   * Intercepts a request to start a Nexus operation.
   *
   * @param input operation start request
   * @return output containing the start response
   * @throws OperationException if the operation fails
   */
  StartOperationOutput startOperation(StartOperationInput input) throws OperationException;

  /**
   * Intercepts a request to fetch the result of a Nexus operation.
   *
   * @param input operation result request
   * @return output containing the operation result
   * @throws OperationException if the operation failed
   * @throws OperationStillRunningException if the operation is still running
   */
  FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationException, OperationStillRunningException;

  /**
   * Intercepts a request to fetch information about a Nexus operation.
   *
   * @param input operation info request
   * @return output containing the operation information
   */
  FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input);

  /**
   * Intercepts a request to cancel a Nexus operation.
   *
   * @param input cancellation request
   * @return output containing the cancellation result
   */
  CancelOperationOutput cancelOperation(CancelOperationInput input);

  /**
   * Intercepts a request to complete a Nexus operation.
   *
   * @param input completion request
   * @return output containing the completion result
   */
  CompleteOperationOutput completeOperation(CompleteOperationInput input);

  /**
   * Intercepts an asynchronous request to start a Nexus operation.
   *
   * @param input operation start request
   * @return future containing the start response
   */
  CompletableFuture<StartOperationOutput> startOperationAsync(StartOperationInput input);

  /**
   * Intercepts an asynchronous request to fetch the result of a Nexus operation.
   *
   * @param input operation result request
   * @return future containing the operation result
   */
  CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
      FetchOperationResultInput input);

  /**
   * Intercepts an asynchronous request to fetch information about a Nexus operation.
   *
   * @param input operation info request
   * @return future containing the operation information
   */
  CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
      FetchOperationInfoInput input);

  /**
   * Intercepts an asynchronous request to cancel a Nexus operation.
   *
   * @param input cancellation request
   * @return future containing the cancellation result
   */
  CompletableFuture<CancelOperationOutput> cancelOperationAsync(CancelOperationInput input);

  /**
   * Intercepts an asynchronous request to complete a Nexus operation.
   *
   * @param input completion request
   * @return future containing the completion result
   */
  CompletableFuture<CompleteOperationOutput> completeOperationAsync(
      CompleteOperationAsyncInput input);

  final class StartOperationInput {
    private final String operationName;
    private final String serviceName;
    private final Object input;
    private final StartOperationOptions options;

    public StartOperationInput(
        String operationName, String serviceName, Object input, StartOperationOptions options) {
      this.operationName = operationName;
      this.serviceName = serviceName;
      this.input = input;
      this.options = options;
    }

    public String getOperationName() {
      return operationName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public Object getInput() {
      return input;
    }

    public StartOperationOptions getOptions() {
      return options;
    }
  }

  final class FetchOperationResultInput {
    private final String operationName;
    private final String serviceName;
    private final String operationToken;
    private final FetchOperationResultOptions options;

    public FetchOperationResultInput(
        String operationName,
        String serviceName,
        String operationToken,
        FetchOperationResultOptions options) {
      this.operationName = operationName;
      this.serviceName = serviceName;
      this.operationToken = operationToken;
      this.options = options;
    }

    public String getOperationName() {
      return operationName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public String getOperationToken() {
      return operationToken;
    }

    public FetchOperationResultOptions getOptions() {
      return options;
    }
  }

  final class FetchOperationInfoInput {
    private final String operationName;
    private final String serviceName;
    private final String operationToken;
    private final FetchOperationInfoOptions options;

    public FetchOperationInfoInput(
        String operationName,
        String serviceName,
        String operationToken,
        FetchOperationInfoOptions options) {
      this.operationName = operationName;
      this.serviceName = serviceName;
      this.operationToken = operationToken;
      this.options = options;
    }

    public String getOperationName() {
      return operationName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public String getOperationToken() {
      return operationToken;
    }

    public FetchOperationInfoOptions getOptions() {
      return options;
    }
  }

  final class CancelOperationInput {
    private final String operationName;
    private final String serviceName;
    private final String operationToken;
    private final CancelOperationOptions options;

    public CancelOperationInput(
        String operationName,
        String serviceName,
        String operationToken,
        CancelOperationOptions options) {
      this.operationName = operationName;
      this.serviceName = serviceName;
      this.operationToken = operationToken;
      this.options = options;
    }

    public String getOperationName() {
      return operationName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public String getOperationToken() {
      return operationToken;
    }

    public CancelOperationOptions getOptions() {
      return options;
    }
  }

  final class CompleteOperationInput {
    private final String url;
    private final CompleteOperationOptions options;

    public CompleteOperationInput(String url, CompleteOperationOptions options) {
      this.url = url;
      this.options = options;
    }

    public String getUrl() {
      return url;
    }

    public CompleteOperationOptions getOptions() {
      return options;
    }
  }

  final class CompleteOperationAsyncInput {
    private final String operationToken;
    private final CompleteOperationOptions options;

    public CompleteOperationAsyncInput(String operationToken, CompleteOperationOptions options) {
      this.operationToken = operationToken;
      this.options = options;
    }

    public String getOperationToken() {
      return operationToken;
    }

    public CompleteOperationOptions getOptions() {
      return options;
    }
  }

  final class StartOperationOutput {
    private final io.nexusrpc.client.transport.StartOperationResponse response;

    public StartOperationOutput(io.nexusrpc.client.transport.StartOperationResponse response) {
      this.response = response;
    }

    public io.nexusrpc.client.transport.StartOperationResponse getResponse() {
      return response;
    }
  }

  final class FetchOperationResultOutput {
    private final FetchOperationResultResponse response;

    public FetchOperationResultOutput(FetchOperationResultResponse response) {
      this.response = response;
    }

    public FetchOperationResultResponse getResponse() {
      return response;
    }
  }

  final class FetchOperationInfoOutput {
    private final FetchOperationInfoResponse response;

    public FetchOperationInfoOutput(FetchOperationInfoResponse response) {
      this.response = response;
    }

    public FetchOperationInfoResponse getResponse() {
      return response;
    }
  }

  final class CancelOperationOutput {
    private final CancelOperationResponse response;

    public CancelOperationOutput(CancelOperationResponse response) {
      this.response = response;
    }

    public CancelOperationResponse getResponse() {
      return response;
    }
  }

  final class CompleteOperationOutput {
    private final CompleteOperationResponse response;

    public CompleteOperationOutput(CompleteOperationResponse response) {
      this.response = response;
    }

    public CompleteOperationResponse getResponse() {
      return response;
    }
  }
}
