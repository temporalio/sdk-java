package io.temporal.client;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;

/**
 * Convenience base class for {@link NexusClientInterceptor} implementations that need to override
 * only a subset of methods. All methods delegate to the wrapped {@code next} interceptor.
 */
@Experimental
public class NexusClientInterceptorBase implements NexusClientInterceptor {

  private final NexusClientInterceptor next;

  public NexusClientInterceptorBase(NexusClientInterceptor next) {
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
  public CompletableFuture<DescribeNexusOperationExecutionOutput>
      describeNexusOperationExecutionAsync(DescribeNexusOperationExecutionInput input) {
    return next.describeNexusOperationExecutionAsync(input);
  }

  @Override
  public PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input) {
    return next.pollNexusOperationExecution(input);
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input) {
    return next.pollNexusOperationExecutionAsync(input);
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
  public void requestCancelNexusOperationExecution(
      RequestCancelNexusOperationExecutionInput input) {
    next.requestCancelNexusOperationExecution(input);
  }

  @Override
  public void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input) {
    next.terminateNexusOperationExecution(input);
  }

  @Override
  public void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input) {
    next.deleteNexusOperationExecution(input);
  }
}
