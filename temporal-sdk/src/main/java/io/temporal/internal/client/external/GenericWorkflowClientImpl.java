package io.temporal.internal.client.external;

import static io.temporal.serviceclient.MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY;
import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.grpc.Deadline;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubLongPollRpcRetryOptions;
import java.util.Map;
import java.util.concurrent.*;
import javax.annotation.Nonnull;

public final class GenericWorkflowClientImpl implements GenericWorkflowClient {
  private static final ScheduledExecutorService asyncThrottlerExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder()
              .setDaemon(true)
              .setNameFormat("generic-wf-client-async-throttler-%d")
              .build());
  private final WorkflowServiceStubs service;
  private final Scope metricsScope;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions grpcRetryerOptions;

  public GenericWorkflowClientImpl(WorkflowServiceStubs service, Scope metricsScope) {
    this.service = service;
    this.metricsScope = metricsScope;
    RpcRetryOptions rpcRetryOptions =
        RpcRetryOptions.newBuilder()
            .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions());
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.grpcRetryerOptions = new GrpcRetryer.GrpcRetryerOptions(rpcRetryOptions, null);
  }

  @Override
  public StartWorkflowExecutionResponse start(StartWorkflowExecutionRequest request) {
    Map<String, String> tags = tagsForStartWorkflow(request);
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .startWorkflowExecution(request),
        grpcRetryerOptions);
  }

  private static Map<String, String> tagsForStartWorkflow(StartWorkflowExecutionRequest request) {
    return new ImmutableMap.Builder<String, String>(2)
        .put(MetricsTag.WORKFLOW_TYPE, request.getWorkflowType().getName())
        .put(MetricsTag.TASK_QUEUE, request.getTaskQueue().getName())
        .build();
  }

  private static Map<String, String> tagsForNexusOperations(String service, String operation) {
    return new ImmutableMap.Builder<String, String>(2)
        .put(MetricsTag.NEXUS_SERVICE, service)
        .put(MetricsTag.OPERATION_NAME, operation)
        .build();
  }

  @Override
  public void signal(SignalWorkflowExecutionRequest request) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.SIGNAL_NAME, request.getSignalName())
            .build();
    Scope scope = metricsScope.tagged(tags);
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .signalWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public SignalWithStartWorkflowExecutionResponse signalWithStart(
      SignalWithStartWorkflowExecutionRequest request) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.WORKFLOW_TYPE, request.getWorkflowType().getName())
            .put(MetricsTag.TASK_QUEUE, request.getTaskQueue().getName())
            .put(MetricsTag.SIGNAL_NAME, request.getSignalName())
            .build();
    Scope scope = metricsScope.tagged(tags);

    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .signalWithStartWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public void requestCancel(RequestCancelWorkflowExecutionRequest request) {
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .requestCancelWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public void terminate(TerminateWorkflowExecutionRequest request) {
    grpcRetryer.retry(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .terminateWorkflowExecution(request),
        grpcRetryerOptions);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse longPollHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                .withDeadline(deadline)
                .getWorkflowExecutionHistory(request),
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> longPollHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request, @Nonnull Deadline deadline) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .withOption(HISTORY_LONG_POLL_CALL_OPTIONS_KEY, true)
                    .withDeadline(deadline)
                    .getWorkflowExecutionHistory(request)),
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  @Override
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      @Nonnull GetWorkflowExecutionHistoryRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .getWorkflowExecutionHistory(request),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<GetWorkflowExecutionHistoryResponse> getWorkflowExecutionHistoryAsync(
      @Nonnull GetWorkflowExecutionHistoryRequest request) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .getWorkflowExecutionHistory(request)),
        grpcRetryerOptions);
  }

  @Override
  public QueryWorkflowResponse query(QueryWorkflowRequest queryParameters) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1)
            .put(MetricsTag.QUERY_TYPE, queryParameters.getQuery().getQueryType())
            .build();
    Scope scope = metricsScope.tagged(tags);

    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .queryWorkflow(queryParameters),
        grpcRetryerOptions);
  }

  @Override
  public ListWorkflowExecutionsResponse listWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .listWorkflowExecutions(listRequest),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<ListWorkflowExecutionsResponse> listWorkflowExecutionsAsync(
      ListWorkflowExecutionsRequest listRequest) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .listWorkflowExecutions(listRequest)),
        grpcRetryerOptions);
  }

  @Override
  public CountWorkflowExecutionsResponse countWorkflowExecutions(
      CountWorkflowExecutionsRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .countWorkflowExecutions(request),
        grpcRetryerOptions);
  }

  @Override
  public CreateScheduleResponse createSchedule(CreateScheduleRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .createSchedule(request),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<ListSchedulesResponse> listSchedulesAsync(ListSchedulesRequest request) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .listSchedules(request)),
        grpcRetryerOptions);
  }

  @Override
  public UpdateScheduleResponse updateSchedule(UpdateScheduleRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .updateSchedule(request),
        grpcRetryerOptions);
  }

  @Override
  public PatchScheduleResponse patchSchedule(PatchScheduleRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .patchSchedule(request),
        grpcRetryerOptions);
  }

  @Override
  public DeleteScheduleResponse deleteSchedule(DeleteScheduleRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .deleteSchedule(request),
        grpcRetryerOptions);
  }

  @Override
  public DescribeScheduleResponse describeSchedule(DescribeScheduleRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .describeSchedule(request),
        grpcRetryerOptions);
  }

  @Override
  public DescribeWorkflowExecutionResponse describeWorkflowExecution(
      DescribeWorkflowExecutionRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .describeWorkflowExecution(request),
        grpcRetryerOptions);
  }

  private static <T> CompletableFuture<T> toCompletableFuture(
      ListenableFuture<T> listenableFuture) {
    CompletableFuture<T> result = new CompletableFuture<>();
    listenableFuture.addListener(
        () -> {
          try {
            result.complete(listenableFuture.get());
          } catch (ExecutionException e) {
            result.completeExceptionally(e.getCause());
          } catch (Exception e) {
            result.completeExceptionally(e);
          }
        },
        ForkJoinPool.commonPool());
    return result;
  }

  @Override
  public UpdateWorkflowExecutionResponse update(
      @Nonnull UpdateWorkflowExecutionRequest updateParameters, @Nonnull Deadline deadline) {
    Map<String, String> tags = tagsForUpdateWorkflow(updateParameters);
    Scope scope = metricsScope.tagged(tags);

    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withDeadline(deadline)
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .updateWorkflowExecution(updateParameters),
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  private static Map<String, String> tagsForUpdateWorkflow(
      UpdateWorkflowExecutionRequest updateParameters) {
    return new ImmutableMap.Builder<String, String>(1)
        .put(MetricsTag.UPDATE_NAME, updateParameters.getRequest().getInput().getName())
        .build();
  }

  @Override
  public CompletableFuture<PollWorkflowExecutionUpdateResponse> pollUpdateAsync(
      @Nonnull PollWorkflowExecutionUpdateRequest request, @Nonnull Deadline deadline) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withDeadline(deadline)
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .pollWorkflowExecutionUpdate(request)),
        new GrpcRetryer.GrpcRetryerOptions(DefaultStubLongPollRpcRetryOptions.INSTANCE, deadline));
  }

  @Override
  public UpdateWorkerBuildIdCompatibilityResponse updateWorkerBuildIdCompatability(
      UpdateWorkerBuildIdCompatibilityRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .updateWorkerBuildIdCompatibility(request),
        grpcRetryerOptions);
  }

  @Override
  public GetWorkerBuildIdCompatibilityResponse getWorkerBuildIdCompatability(
      GetWorkerBuildIdCompatibilityRequest req) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .getWorkerBuildIdCompatibility(req),
        grpcRetryerOptions);
  }

  @Override
  public GetWorkerTaskReachabilityResponse GetWorkerTaskReachability(
      GetWorkerTaskReachabilityRequest req) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .getWorkerTaskReachability(req),
        grpcRetryerOptions);
  }

  @Override
  public ExecuteMultiOperationResponse executeMultiOperation(
      ExecuteMultiOperationRequest req, @Nonnull Deadline deadline) {
    ImmutableMap.Builder<String, String> tags = new ImmutableMap.Builder<>();
    for (int i = 0; i < req.getOperationsCount(); i++) {
      ExecuteMultiOperationRequest.Operation operation = req.getOperations(i);
      if (operation.hasStartWorkflow()) {
        tags.putAll(tagsForStartWorkflow(operation.getStartWorkflow()));
      } else if (operation.hasUpdateWorkflow()) {
        tags.putAll(tagsForUpdateWorkflow(operation.getUpdateWorkflow()));
      }
    }
    Scope scope = metricsScope.tagged(tags.build());

    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withDeadline(deadline)
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .executeMultiOperation(req),
        grpcRetryerOptions);
  }

  @Override
  public GetNexusOperationInfoResponse getNexusOperationInfo(GetNexusOperationInfoRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .getNexusOperationInfo(request),
        grpcRetryerOptions);
  }

  @Override
  public StartNexusOperationResponse startNexusOperation(StartNexusOperationRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .startNexusOperation(request),
        grpcRetryerOptions);
  }

  @Override
  public RequestCancelNexusOperationResponse requestCancelNexusOperation(
      RequestCancelNexusOperationRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                .requestCancelNexusOperation(request),
        grpcRetryerOptions);
  }

  @Override
  public GetNexusOperationResultResponse getNexusOperationResult(
      GetNexusOperationResultRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    //    Deadline deadline =
    //        Deadline.after(request.getWait().getSeconds() * 1000, TimeUnit.MILLISECONDS);
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                // .withDeadline(deadline)
                .getNexusOperationResult(request),
        grpcRetryerOptions);
  }

  @Override
  public CompleteNexusOperationResponse completeNexusOperation(
      CompleteNexusOperationRequest request) {
    return grpcRetryer.retryWithResult(
        () ->
            service
                .blockingStub()
                .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                .completeNexusOperation(request),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<GetNexusOperationInfoResponse> getNexusOperationInfoAsync(
      GetNexusOperationInfoRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .getNexusOperationInfo(request)),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<StartNexusOperationResponse> startNexusOperationAsync(
      StartNexusOperationRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .startNexusOperation(request)),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<RequestCancelNexusOperationResponse> requestCancelNexusOperationAsync(
      RequestCancelNexusOperationRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .requestCancelNexusOperation(request)),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<GetNexusOperationResultResponse> getNexusOperationResultAsync(
      GetNexusOperationResultRequest request) {
    Map<String, String> tags = tagsForNexusOperations(request.getService(), request.getOperation());
    Scope scope = metricsScope.tagged(tags);
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, scope)
                    .getNexusOperationResult(request)),
        grpcRetryerOptions);
  }

  @Override
  public CompletableFuture<CompleteNexusOperationResponse> completeNexusOperationAsync(
      CompleteNexusOperationRequest request) {
    return grpcRetryer.retryWithResultAsync(
        asyncThrottlerExecutor,
        () ->
            toCompletableFuture(
                service
                    .futureStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .completeNexusOperation(request)),
        grpcRetryerOptions);
  }
}
