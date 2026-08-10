package io.temporal.internal.client.external;

import com.google.common.base.Strings;
import io.grpc.Deadline;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.payload.storage.ExternalStorageMessageTransformer;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Decorates a {@link GenericWorkflowClient} to offload outbound request payloads to external
 * storage and restore inbound response payloads.
 *
 * <p>Only constructed when external storage is configured, so the converter is never null.
 */
public final class ExternalStorageGenericWorkflowClient implements GenericWorkflowClient {
  private final GenericWorkflowClient next;
  private final ExternalStorageMessageTransformer externalStorage;
  private final String namespace;

  public ExternalStorageGenericWorkflowClient(
      GenericWorkflowClient next,
      ExternalStorageMessageTransformer externalStorage,
      String namespace) {
    this.next = next;
    this.externalStorage = externalStorage;
    this.namespace = namespace;
  }

  @Nullable
  private StorageDriverTargetInfo workflowTarget(String workflowId, String runId, String type) {
    return new StorageDriverWorkflowInfo(
        namespace,
        Strings.emptyToNull(workflowId),
        Strings.emptyToNull(runId),
        Strings.emptyToNull(type));
  }

  @Nullable
  private StorageDriverTargetInfo workflowTarget(WorkflowExecution execution, String type) {
    return workflowTarget(execution.getWorkflowId(), execution.getRunId(), type);
  }

  @Nullable
  private StorageDriverTargetInfo multiOperationTarget(ExecuteMultiOperationRequest request) {
    for (ExecuteMultiOperationRequest.Operation operation : request.getOperationsList()) {
      if (operation.hasStartWorkflow()) {
        StartWorkflowExecutionRequest start = operation.getStartWorkflow();
        return workflowTarget(start.getWorkflowId(), null, start.getWorkflowType().getName());
      }
    }
    return null;
  }

  @Override
  public StartWorkflowExecutionResponse start(StartWorkflowExecutionRequest request) {
    return next.start(
        externalStorage.storeBlocking(
            request,
            workflowTarget(request.getWorkflowId(), null, request.getWorkflowType().getName())));
  }

  @Override
  public SignalWorkflowExecutionResponse signal(SignalWorkflowExecutionRequest request) {
    return next.signal(
        externalStorage.storeBlocking(
            request, workflowTarget(request.getWorkflowExecution(), null)));
  }

  @Override
  public SignalWithStartWorkflowExecutionResponse signalWithStart(
      SignalWithStartWorkflowExecutionRequest request) {
    return next.signalWithStart(
        externalStorage.storeBlocking(
            request,
            workflowTarget(request.getWorkflowId(), null, request.getWorkflowType().getName())));
  }

  @Override
  public void requestCancel(RequestCancelWorkflowExecutionRequest parameters) {
    next.requestCancel(parameters);
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryParameters) {
    QueryWorkflowRequest stored =
        externalStorage.storeBlocking(
            queryParameters, workflowTarget(queryParameters.getExecution(), null));
    return externalStorage.retrieveBlocking(next.query(stored));
  }

  @Override
  public UpdateWorkflowExecutionResponse update(
      @Nonnull UpdateWorkflowExecutionRequest updateParameters, @Nonnull Deadline deadline) {
    UpdateWorkflowExecutionRequest stored =
        externalStorage.storeBlocking(
            updateParameters, workflowTarget(updateParameters.getWorkflowExecution(), null));
    return externalStorage.retrieveBlocking(next.update(stored, deadline));
  }

  @Override
  public CompletableFuture<PollWorkflowExecutionUpdateResponse> pollUpdateAsync(
      @Nonnull PollWorkflowExecutionUpdateRequest request, @Nonnull Deadline deadline) {
    return next.pollUpdateAsync(request, deadline).thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public void terminate(TerminateWorkflowExecutionRequest request) {
    next.terminate(
        externalStorage.storeBlocking(
            request, workflowTarget(request.getWorkflowExecution(), null)));
  }

  @Override
  public GetWorkflowExecutionHistoryResponse longPollHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return externalStorage.retrieveBlocking(next.longPollHistory(request, deadline));
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return next.longPollHistoryAsync(request, deadline).thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request) {
    return externalStorage.retrieveBlocking(next.getWorkflowExecutionHistory(request));
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> getWorkflowExecutionHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request) {
    return next.getWorkflowExecutionHistoryAsync(request)
        .thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public ListWorkflowExecutionsResponse listWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest) {
    return externalStorage.retrieveBlocking(next.listWorkflowExecutions(listRequest));
  }

  @Override
  public CompletableFuture<ListWorkflowExecutionsResponse> listWorkflowExecutionsAsync(
      ListWorkflowExecutionsRequest listRequest) {
    return next.listWorkflowExecutionsAsync(listRequest)
        .thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public CountWorkflowExecutionsResponse countWorkflowExecutions(
      CountWorkflowExecutionsRequest request) {
    return next.countWorkflowExecutions(request);
  }

  @Override
  public CreateScheduleResponse createSchedule(CreateScheduleRequest request) {
    return next.createSchedule(externalStorage.storeBlocking(request, null));
  }

  @Override
  public CompletableFuture<ListSchedulesResponse> listSchedulesAsync(ListSchedulesRequest request) {
    return next.listSchedulesAsync(request).thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public UpdateScheduleResponse updateSchedule(UpdateScheduleRequest request) {
    return next.updateSchedule(externalStorage.storeBlocking(request, null));
  }

  @Override
  public PatchScheduleResponse patchSchedule(PatchScheduleRequest request) {
    return next.patchSchedule(request);
  }

  @Override
  public DeleteScheduleResponse deleteSchedule(DeleteScheduleRequest request) {
    return next.deleteSchedule(request);
  }

  @Override
  public DescribeScheduleResponse describeSchedule(DescribeScheduleRequest request) {
    return externalStorage.retrieveBlocking(next.describeSchedule(request));
  }

  @Override
  public DescribeWorkflowExecutionResponse describeWorkflowExecution(
      DescribeWorkflowExecutionRequest request) {
    return externalStorage.retrieveBlocking(next.describeWorkflowExecution(request));
  }

  @Override
  public StartNexusOperationExecutionResponse startNexusOperationExecution(
      @Nonnull StartNexusOperationExecutionRequest request) {
    return next.startNexusOperationExecution(externalStorage.storeBlocking(request, null));
  }

  @Override
  public DescribeNexusOperationExecutionResponse describeNexusOperationExecution(
      @Nonnull DescribeNexusOperationExecutionRequest request) {
    return externalStorage.retrieveBlocking(next.describeNexusOperationExecution(request));
  }

  @Override
  public PollNexusOperationExecutionResponse pollNexusOperationExecution(
      @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline) {
    return externalStorage.retrieveBlocking(next.pollNexusOperationExecution(request, deadline));
  }

  @Override
  public CompletableFuture<PollNexusOperationExecutionResponse> pollNexusOperationExecutionAsync(
      @Nonnull PollNexusOperationExecutionRequest request, @Nonnull Deadline deadline) {
    return next.pollNexusOperationExecutionAsync(request, deadline)
        .thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public CompletableFuture<ListNexusOperationExecutionsResponse> listNexusOperationExecutionsAsync(
      @Nonnull ListNexusOperationExecutionsRequest request) {
    return next.listNexusOperationExecutionsAsync(request)
        .thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public CountNexusOperationExecutionsResponse countNexusOperationExecutions(
      @Nonnull CountNexusOperationExecutionsRequest request) {
    return next.countNexusOperationExecutions(request);
  }

  @Override
  public RequestCancelNexusOperationExecutionResponse requestCancelNexusOperationExecution(
      @Nonnull RequestCancelNexusOperationExecutionRequest request) {
    return next.requestCancelNexusOperationExecution(request);
  }

  @Override
  public TerminateNexusOperationExecutionResponse terminateNexusOperationExecution(
      @Nonnull TerminateNexusOperationExecutionRequest request) {
    return next.terminateNexusOperationExecution(request);
  }

  @Override
  public DeleteNexusOperationExecutionResponse deleteNexusOperationExecution(
      @Nonnull DeleteNexusOperationExecutionRequest request) {
    return next.deleteNexusOperationExecution(request);
  }

  @Override
  @SuppressWarnings("deprecation")
  public UpdateWorkerBuildIdCompatibilityResponse updateWorkerBuildIdCompatability(
      UpdateWorkerBuildIdCompatibilityRequest request) {
    return next.updateWorkerBuildIdCompatability(request);
  }

  @Override
  public ExecuteMultiOperationResponse executeMultiOperation(
      ExecuteMultiOperationRequest request, @Nonnull Deadline deadline) {
    ExecuteMultiOperationRequest stored =
        externalStorage.storeBlocking(request, multiOperationTarget(request));
    return externalStorage.retrieveBlocking(next.executeMultiOperation(stored, deadline));
  }

  @Override
  public StartActivityExecutionResponse startActivity(StartActivityExecutionRequest request) {
    return next.startActivity(
        externalStorage.storeBlocking(
            request,
            new StorageDriverActivityInfo(
                namespace,
                Strings.emptyToNull(request.getActivityId()),
                null,
                Strings.emptyToNull(request.getActivityType().getName()))));
  }

  @Override
  public PollActivityExecutionResponse pollActivity(PollActivityExecutionRequest request) {
    return externalStorage.retrieveBlocking(next.pollActivity(request));
  }

  @Override
  public PollActivityExecutionResponse pollActivity(
      PollActivityExecutionRequest request, @Nonnull Deadline deadline) {
    return externalStorage.retrieveBlocking(next.pollActivity(request, deadline));
  }

  @Override
  public CompletableFuture<PollActivityExecutionResponse> pollActivityAsync(
      PollActivityExecutionRequest request, @Nonnull Deadline deadline) {
    return next.pollActivityAsync(request, deadline).thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public DescribeActivityExecutionResponse describeActivity(
      DescribeActivityExecutionRequest request) {
    return externalStorage.retrieveBlocking(next.describeActivity(request));
  }

  @Override
  public void cancelActivity(RequestCancelActivityExecutionRequest request) {
    next.cancelActivity(request);
  }

  @Override
  public void terminateActivity(TerminateActivityExecutionRequest request) {
    next.terminateActivity(request);
  }

  @Override
  public ListActivityExecutionsResponse listActivities(ListActivityExecutionsRequest request) {
    return externalStorage.retrieveBlocking(next.listActivities(request));
  }

  @Override
  public CompletableFuture<ListActivityExecutionsResponse> listActivitiesAsync(
      ListActivityExecutionsRequest request) {
    return next.listActivitiesAsync(request).thenCompose(externalStorage::retrieveAsync);
  }

  @Override
  public CountActivityExecutionsResponse countActivities(CountActivityExecutionsRequest request) {
    return next.countActivities(request);
  }

  @Override
  @SuppressWarnings("deprecation")
  public GetWorkerBuildIdCompatibilityResponse getWorkerBuildIdCompatability(
      GetWorkerBuildIdCompatibilityRequest req) {
    return next.getWorkerBuildIdCompatability(req);
  }

  @Override
  @SuppressWarnings("deprecation")
  public GetWorkerTaskReachabilityResponse GetWorkerTaskReachability(
      GetWorkerTaskReachabilityRequest req) {
    return next.GetWorkerTaskReachability(req);
  }
}
