package io.temporal.client;

import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubs;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Handle for interacting with a standalone Nexus operation execution.
 *
 * <p>Returned by {@link WorkflowClient} when starting a Nexus operation, and also constructable
 * from an existing operation ID for operating on an operation that was started elsewhere.
 */
@Experimental
public interface NexusClient {
  public static NexusClient newInstance(WorkflowServiceStubs service) {
    return NexusClientImpl.newInstance(service, NexusClientOperationOptions.getDefaultInstance());
  }

  //Look at ScheduleClientOptions for an example
  public static NexusClient newInstance(WorkflowServiceStubs service, NexusClientOperationOptions options) {
    return NexusClientImpl.newInstance(service, options());
  }


  public NexusClientHandle getHandle(String scheduleID);


  UntypedNexusClientHandle getHandle(
          String operationId,
          @Nullable String operationRunId);


  //Handle is a pointer to an already started workflow
  //I will need to create this handle
  //Create a NexusOperationHandlerImpl and pass everything needed in
  //to create this handle
  //Follow what schedule client does
  //See it's get handle. etc

  /// Obtains typed handle to existing operations.
  <R> NexusClientHandle<R> getHandle(
          String operationId,
          @Nullable String operationRunId,
          Class<R> resultClass);

  /// Obtains typed handle to existing operations.
  /// For use with generic return types.
  <R> NexusClientHandle<R> getHandle(
          String operationId,
          @Nullable String operationRunId,
          Class<R> resultClass,
          @Nullable Type resultType);

  //untyped -- see the notion doc
  //untyped means I don't have the services type, ust the name
  UntypedNexusServiceClient newUntypeNexusServiceClient();

  <R> NexusServiceClient<R> newNexusServiceClient();




  //ListNexusOperationExecutionsResponse -- look at the go code to see this
  //It is everything we expose for a list
  //This should call the nexus list operation
  //Again, look at schedule client to see or the ListWorkflowExecutionsOutput om WorkflowClientCallsInterceptor
//  Stream<NexusOperationExecutionMetadata> listNexusOperations(String query);
//
//  NexusOperationExecutionCount countNexusOperations(String query);
//TODO - EVAN


  StartNexusOperationExecutionResponse startNexusOperationExecution(
          @Nonnull StartNexusOperationExecutionRequest request);

  DescribeNexusOperationExecutionResponse describeNexusOperationExecution(
          @Nonnull DescribeNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

  CompletableFuture<DescribeNexusOperationExecutionResponse> describeNexusOperationExecutionAsync(
          @Nonnull DescribeNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

  PollNexusOperationExecutionResponse pollNexusOperationExecution(
          @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

  CompletableFuture<PollNexusOperationExecutionResponse> pollNexusOperationExecutionAsync(
          @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline);

  ListNexusOperationExecutionsResponse listNexusOperationExecutions(
          @Nonnull ListNexusOperationExecutionsRequest request);

  CountNexusOperationExecutionsResponse countNexusOperationExecutions(
          @Nonnull CountNexusOperationExecutionsRequest request);

  RequestCancelNexusOperationExecutionResponse requestCancelNexusOperationExecution(
          @Nonnull RequestCancelNexusOperationExecutionRequest request);

  TerminateNexusOperationExecutionResponse terminateNexusOperationExecution(
          @Nonnull TerminateNexusOperationExecutionRequest request);

  DeleteNexusOperationExecutionResponse deleteNexusOperationExecution(
          @Nonnull DeleteNexusOperationExecutionRequest request);

}
