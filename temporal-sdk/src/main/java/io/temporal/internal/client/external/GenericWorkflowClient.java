package io.temporal.internal.client.external;

import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public interface GenericWorkflowClient {

  StartWorkflowExecutionResponse start(StartWorkflowExecutionRequest request);

  void signal(SignalWorkflowExecutionRequest request);

  SignalWithStartWorkflowExecutionResponse signalWithStart(
      SignalWithStartWorkflowExecutionRequest request);

  void requestCancel(RequestCancelWorkflowExecutionRequest parameters);

  QueryWorkflowResponse query(QueryWorkflowRequest queryParameters);

  UpdateWorkflowExecutionResponse update(
      @Nonnull UpdateWorkflowExecutionRequest updateParameters, @Nonnull Deadline deadline);

  CompletableFuture<PollWorkflowExecutionUpdateResponse> pollUpdateAsync(
      @Nonnull PollWorkflowExecutionUpdateRequest request, @Nonnull Deadline deadline);

  void terminate(TerminateWorkflowExecutionRequest request);

  GetWorkflowExecutionHistoryResponse longPollHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline);

  CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline);

  GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request);

  CompletableFuture<GetWorkflowExecutionHistoryResponse> getWorkflowExecutionHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request);

  ListWorkflowExecutionsResponse listWorkflowExecutions(ListWorkflowExecutionsRequest listRequest);

  CompletableFuture<ListWorkflowExecutionsResponse> listWorkflowExecutionsAsync(
      ListWorkflowExecutionsRequest listRequest);

  CountWorkflowExecutionsResponse countWorkflowExecutions(CountWorkflowExecutionsRequest request);

  CreateScheduleResponse createSchedule(CreateScheduleRequest request);

  CompletableFuture<ListSchedulesResponse> listSchedulesAsync(ListSchedulesRequest request);

  UpdateScheduleResponse updateSchedule(UpdateScheduleRequest request);

  PatchScheduleResponse patchSchedule(PatchScheduleRequest request);

  DeleteScheduleResponse deleteSchedule(DeleteScheduleRequest request);

  DescribeScheduleResponse describeSchedule(DescribeScheduleRequest request);

  DescribeWorkflowExecutionResponse describeWorkflowExecution(
      DescribeWorkflowExecutionRequest request);

  StartNexusOperationExecutionResponse startNexusOperationExecution(
      @Nonnull StartNexusOperationExecutionRequest request);

  DescribeNexusOperationExecutionResponse describeNexusOperationExecution(
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

  @Experimental
  @Deprecated
  UpdateWorkerBuildIdCompatibilityResponse updateWorkerBuildIdCompatability(
      UpdateWorkerBuildIdCompatibilityRequest request);

  @Experimental
  ExecuteMultiOperationResponse executeMultiOperation(
      ExecuteMultiOperationRequest request, @Nonnull Deadline deadline);

  @Experimental
  @Deprecated
  GetWorkerBuildIdCompatibilityResponse getWorkerBuildIdCompatability(
      GetWorkerBuildIdCompatibilityRequest req);

  @Experimental
  @Deprecated
  GetWorkerTaskReachabilityResponse GetWorkerTaskReachability(GetWorkerTaskReachabilityRequest req);
}
