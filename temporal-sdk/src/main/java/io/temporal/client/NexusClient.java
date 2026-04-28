package io.temporal.client;

import io.temporal.client.NexusClientInterceptor.CountNexusOperationExecutionsInput;
import io.temporal.client.NexusClientInterceptor.CountNexusOperationExecutionsOutput;
import io.temporal.client.NexusClientInterceptor.DeleteNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.DescribeNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.DescribeNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.ListNexusOperationExecutionsInput;
import io.temporal.client.NexusClientInterceptor.ListNexusOperationExecutionsOutput;
import io.temporal.client.NexusClientInterceptor.PollNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.PollNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.RequestCancelNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.StartNexusOperationExecutionInput;
import io.temporal.client.NexusClientInterceptor.StartNexusOperationExecutionOutput;
import io.temporal.client.NexusClientInterceptor.TerminateNexusOperationExecutionInput;
import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Handle for interacting with a standalone Nexus operation execution.
 *
 * <p>Returned by {@link WorkflowClient} when starting a Nexus operation, and also constructable
 * from an existing operation ID for operating on an operation that was started elsewhere.
 */
@Experimental
public interface NexusClient {
  static NexusClient newInstance(WorkflowServiceStubs service) {
    return NexusClientImpl.newInstance(service, NexusClientOperationOptions.getDefaultInstance());
  }

  static NexusClient newInstance(
      WorkflowServiceStubs service, NexusClientOperationOptions options) {
    return NexusClientImpl.newInstance(service, options);
  }

  NexusClientHandle getHandle(String scheduleID);

  UntypedNexusClientHandle getHandle(String operationId, @Nullable String operationRunId);

  /** Obtains typed handle to existing operations. */
  <R> NexusClientHandle<R> getHandle(
      String operationId, @Nullable String operationRunId, Class<R> resultClass);

  /** Obtains typed handle to existing operations. For use with generic return types. */
  <R> NexusClientHandle<R> getHandle(
      String operationId,
      @Nullable String operationRunId,
      Class<R> resultClass,
      @Nullable Type resultType);

  UntypedNexusServiceClient newUntypeNexusServiceClient();

  <R> NexusServiceClient<R> newNexusServiceClient();

  StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input);

  DescribeNexusOperationExecutionOutput describeNexusOperationExecution(
      DescribeNexusOperationExecutionInput input);

  CompletableFuture<DescribeNexusOperationExecutionOutput> describeNexusOperationExecutionAsync(
      DescribeNexusOperationExecutionInput input);

  PollNexusOperationExecutionOutput pollNexusOperationExecution(
      PollNexusOperationExecutionInput input);

  CompletableFuture<PollNexusOperationExecutionOutput> pollNexusOperationExecutionAsync(
      PollNexusOperationExecutionInput input);

  ListNexusOperationExecutionsOutput listNexusOperationExecutions(
      ListNexusOperationExecutionsInput input);

  CountNexusOperationExecutionsOutput countNexusOperationExecutions(
      CountNexusOperationExecutionsInput input);

  void requestCancelNexusOperationExecution(RequestCancelNexusOperationExecutionInput input);

  void terminateNexusOperationExecution(TerminateNexusOperationExecutionInput input);

  void deleteNexusOperationExecution(DeleteNexusOperationExecutionInput input);
}
