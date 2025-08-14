package io.temporal.common.interceptors;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import java.util.concurrent.CompletableFuture;

/** Convenience base class for {@link NexusServiceClientInterceptor} implementations. */
public class NexusServiceClientInterceptorBase implements NexusServiceClientInterceptor {

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
}
