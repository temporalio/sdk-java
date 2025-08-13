package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.client.StartOperationResponse;
import io.nexusrpc.client.transport.CancelOperationOptions;
import io.nexusrpc.client.transport.CancelOperationResponse;
import io.nexusrpc.client.transport.CompleteOperationOptions;
import io.nexusrpc.client.transport.CompleteOperationResponse;
import io.nexusrpc.client.transport.FetchOperationInfoOptions;
import io.nexusrpc.client.transport.FetchOperationInfoResponse;
import io.nexusrpc.client.transport.FetchOperationResultOptions;
import io.nexusrpc.client.transport.FetchOperationResultResponse;
import io.nexusrpc.client.transport.StartOperationOptions;
import io.nexusrpc.client.transport.Transport;
import java.util.concurrent.CompletableFuture;

/** Convenience base class for {@link NexusServiceClientInterceptor} implementations. */
public class NexusServiceClientInterceptorBase implements NexusServiceClientInterceptor, Transport {

  private final NexusServiceClientInterceptor next;

  public NexusServiceClientInterceptorBase(NexusServiceClientInterceptor next) {
    this.next = next;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    return next.startOperation(input);
  }

  @Override
  public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationException, OperationStillRunningException {
    return next.fetchOperationResult(input);
  }

  @Override
  public FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input) {
    return next.fetchOperationInfo(input);
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    return next.cancelOperation(input);
  }

  @Override
  public CompleteOperationOutput completeOperation(CompleteOperationInput input) {
    return next.completeOperation(input);
  }

  @Override
  public CompletableFuture<StartOperationOutput> startOperationAsync(StartOperationInput input) {
    return next.startOperationAsync(input);
  }

  @Override
  public CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
      FetchOperationResultInput input) {
    return next.fetchOperationResultAsync(input);
  }

  @Override
  public CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
      FetchOperationInfoInput input) {
    return next.fetchOperationInfoAsync(input);
  }

  @Override
  public CompletableFuture<CancelOperationOutput> cancelOperationAsync(CancelOperationInput input) {
    return next.cancelOperationAsync(input);
  }

  @Override
  public CompletableFuture<CompleteOperationOutput> completeOperationAsync(
      CompleteOperationAsyncInput input) {
    return next.completeOperationAsync(input);
  }

  // Transport implementation
  @Override
  public StartOperationResponse startOperation(
      String operationName, String serviceName, Object input, StartOperationOptions options)
      throws OperationException {
    return startOperation(new StartOperationInput(operationName, serviceName, input, options))
        .getResponse();
  }

  @Override
  public FetchOperationResultResponse fetchOperationResult(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options)
      throws OperationException, OperationStillRunningException {
    return fetchOperationResult(
            new FetchOperationResultInput(operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public FetchOperationInfoResponse fetchOperationInfo(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return fetchOperationInfo(
            new FetchOperationInfoInput(operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public CancelOperationResponse cancelOperation(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return cancelOperation(
            new CancelOperationInput(operationName, serviceName, operationToken, options))
        .getResponse();
  }

  @Override
  public CompleteOperationResponse completeOperation(String url, CompleteOperationOptions options) {
    return completeOperation(new CompleteOperationInput(url, options)).getResponse();
  }

  @Override
  public CompletableFuture<StartOperationResponse> startOperationAsync(
      String operationName, String serviceName, Object input, StartOperationOptions options) {
    return startOperationAsync(new StartOperationInput(operationName, serviceName, input, options))
        .thenApply(StartOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationResultResponse> fetchOperationResultAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationResultOptions options) {
    return fetchOperationResultAsync(
            new FetchOperationResultInput(operationName, serviceName, operationToken, options))
        .thenApply(FetchOperationResultOutput::getResponse);
  }

  @Override
  public CompletableFuture<FetchOperationInfoResponse> fetchOperationInfoAsync(
      String operationName,
      String serviceName,
      String operationToken,
      FetchOperationInfoOptions options) {
    return fetchOperationInfoAsync(
            new FetchOperationInfoInput(operationName, serviceName, operationToken, options))
        .thenApply(FetchOperationInfoOutput::getResponse);
  }

  @Override
  public CompletableFuture<CancelOperationResponse> cancelOperationAsync(
      String operationName,
      String serviceName,
      String operationToken,
      CancelOperationOptions options) {
    return cancelOperationAsync(
            new CancelOperationInput(operationName, serviceName, operationToken, options))
        .thenApply(CancelOperationOutput::getResponse);
  }

  @Override
  public CompletableFuture<CompleteOperationResponse> completeOperationAsync(
      String operationToken, CompleteOperationOptions options) {
    return completeOperationAsync(new CompleteOperationAsyncInput(operationToken, options))
        .thenApply(CompleteOperationOutput::getResponse);
  }
}
