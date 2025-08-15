package io.temporal.client;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.transport.*;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptor;
import java.util.concurrent.CompletableFuture;

class temporalNexusTransport implements Transport {
  private final NexusServiceClientCallsInterceptor interceptor;

  public temporalNexusTransport(NexusServiceClientCallsInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public StartOperationResponse startOperation(
      String operationName, String serviceName, Object input, StartOperationOptions options)
      throws OperationException {
    return interceptor
        .startOperation(
            new NexusServiceClientCallsInterceptor.StartOperationInput(
                operationName, serviceName, input, options))
        .getResponse();
  }

  @Override
  public FetchOperationResultResponse fetchOperationResult(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options)
      throws OperationException, OperationStillRunningException {
    return interceptor
        .fetchOperationResult(
            new NexusServiceClientCallsInterceptor.FetchOperationResultInput(
                operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public FetchOperationInfoResponse fetchOperationInfo(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return interceptor
        .fetchOperationInfo(
            new NexusServiceClientCallsInterceptor.FetchOperationInfoInput(
                operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public CancelOperationResponse cancelOperation(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return interceptor
        .cancelOperation(
            new NexusServiceClientCallsInterceptor.CancelOperationInput(
                operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public CompleteOperationResponse completeOperation(String url, CompleteOperationOptions options) {
    return interceptor
        .completeOperation(
            new NexusServiceClientCallsInterceptor.CompleteOperationInput(url, options))
        .getResponse();
  }

  @Override
  public CompletableFuture<StartOperationResponse> startOperationAsync(
      String operationName, String serviceName, Object input, StartOperationOptions options) {
    return interceptor
        .startOperationAsync(
            new NexusServiceClientCallsInterceptor.StartOperationInput(
                operationName, serviceName, input, options))
        .thenApply(NexusServiceClientCallsInterceptor.StartOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationResultResponse> fetchOperationResultAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options) {
    return interceptor
        .fetchOperationResultAsync(
            new NexusServiceClientCallsInterceptor.FetchOperationResultInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientCallsInterceptor.FetchOperationResultOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationInfoResponse> fetchOperationInfoAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return interceptor
        .fetchOperationInfoAsync(
            new NexusServiceClientCallsInterceptor.FetchOperationInfoInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientCallsInterceptor.FetchOperationInfoOutput::getResponse);
  }

  @Override
  public CompletableFuture<CancelOperationResponse> cancelOperationAsync(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return interceptor
        .cancelOperationAsync(
            new NexusServiceClientCallsInterceptor.CancelOperationInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientCallsInterceptor.CancelOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<CompleteOperationResponse> completeOperationAsync(
      String url, CompleteOperationOptions options) {
    return interceptor
        .completeOperationAsync(
            new NexusServiceClientCallsInterceptor.CompleteOperationAsyncInput(url, options))
        .thenApply(NexusServiceClientCallsInterceptor.CompleteOperationOutput::getResponse);
  }
}
