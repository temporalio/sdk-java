package io.temporal.client;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.transport.*;
import io.temporal.common.interceptors.NexusServiceClientInterceptor;
import java.util.concurrent.CompletableFuture;

class temporalTransport implements Transport {
  private final NexusServiceClientInterceptor interceptor;

  public temporalTransport(NexusServiceClientInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public io.nexusrpc.client.transport.StartOperationResponse startOperation(
      String operationName, String serviceName, Object input, StartOperationOptions options)
      throws OperationException {
    return interceptor
        .startOperation(
            new NexusServiceClientInterceptor.StartOperationInput(
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
            new NexusServiceClientInterceptor.FetchOperationResultInput(
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
            new NexusServiceClientInterceptor.FetchOperationInfoInput(
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
            new NexusServiceClientInterceptor.CancelOperationInput(
                operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public CompleteOperationResponse completeOperation(String url, CompleteOperationOptions options) {
    return interceptor
        .completeOperation(new NexusServiceClientInterceptor.CompleteOperationInput(url, options))
        .getResponse();
  }

  @Override
  public CompletableFuture<StartOperationResponse> startOperationAsync(
      String operationName, String serviceName, Object input, StartOperationOptions options) {
    return interceptor
        .startOperationAsync(
            new NexusServiceClientInterceptor.StartOperationInput(
                operationName, serviceName, input, options))
        .thenApply(NexusServiceClientInterceptor.StartOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationResultResponse> fetchOperationResultAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options) {
    return interceptor
        .fetchOperationResultAsync(
            new NexusServiceClientInterceptor.FetchOperationResultInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientInterceptor.FetchOperationResultOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationInfoResponse> fetchOperationInfoAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return interceptor
        .fetchOperationInfoAsync(
            new NexusServiceClientInterceptor.FetchOperationInfoInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientInterceptor.FetchOperationInfoOutput::getResponse);
  }

  @Override
  public CompletableFuture<CancelOperationResponse> cancelOperationAsync(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return interceptor
        .cancelOperationAsync(
            new NexusServiceClientInterceptor.CancelOperationInput(
                operationName, serviceName, operationToken, options))
        .thenApply(NexusServiceClientInterceptor.CancelOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<CompleteOperationResponse> completeOperationAsync(
      String operationToken, CompleteOperationOptions options) {
    return interceptor
        .completeOperationAsync(
            new NexusServiceClientInterceptor.CompleteOperationAsyncInput(operationToken, options))
        .thenApply(NexusServiceClientInterceptor.CompleteOperationOutput::getResponse);
  }
}
