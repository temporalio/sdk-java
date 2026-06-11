package io.temporal.common.interceptors;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Convenience base class for {@link NexusClientCallsInterceptor} implementations that need to
 * override only a subset of methods. All methods delegate to the wrapped {@code next} interceptor.
 */
@Experimental
public class NexusClientCallsInterceptorBase implements NexusClientCallsInterceptor {

  private final NexusClientCallsInterceptor next;

  public NexusClientCallsInterceptorBase(NexusClientCallsInterceptor next) {
    this.next = next;
  }

  @Override
  public StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input) {
    return next.startNexusOperationExecution(input);
  }

  @Override
  public DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input) {
    return next.describeNexusOperationExecution(input);
  }

  @Override
  public <R> GetNexusOperationResultOutput<R> getNexusOperationResult(
      GetNexusOperationResultInput<R> input) throws TimeoutException {
    return next.getNexusOperationResult(input);
  }

  @Override
  public <R> CompletableFuture<GetNexusOperationResultOutput<R>> getNexusOperationResultAsync(
      GetNexusOperationResultInput<R> input) {
    return next.getNexusOperationResultAsync(input);
  }

  @Override
  public ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input) {
    return next.listNexusOperationExecutions(input);
  }

  @Override
  public CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input) {
    return next.countNexusOperationExecutions(input);
  }

  @Override
  public RequestCancelNexusOperationExecutionOutput requestCancelNexusOperationExecution(
      RequestCancelNexusOperationExecutionInput input) {
    return next.requestCancelNexusOperationExecution(input);
  }

  @Override
  public TerminateNexusOperationExecutionOutput terminateNexusOperationExecution(
      TerminateNexusOperationExecutionInput input) {
    return next.terminateNexusOperationExecution(input);
  }

  @Override
  public DeleteNexusOperationExecutionOutput deleteNexusOperationExecution(
      DeleteNexusOperationExecutionInput input) {
    return next.deleteNexusOperationExecution(input);
  }
}
