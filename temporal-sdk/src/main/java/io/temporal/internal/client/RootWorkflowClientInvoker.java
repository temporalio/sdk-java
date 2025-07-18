package io.temporal.internal.client;

import static io.temporal.api.workflowservice.v1.ExecuteMultiOperationResponse.Response.ResponseCase.START_WORKFLOW;
import static io.temporal.api.workflowservice.v1.ExecuteMultiOperationResponse.Response.ResponseCase.UPDATE_WORKFLOW;
import static io.temporal.internal.common.HeaderUtils.intoPayloadMap;
import static io.temporal.internal.common.WorkflowExecutionUtils.makeUserMetaData;

import com.google.common.collect.Iterators;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.errordetails.v1.MultiOperationExecutionFailure;
import io.temporal.api.failure.v1.MultiOperationExecutionAborted;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.update.v1.*;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.worker.WorkflowTaskDispatchHandle;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RootWorkflowClientInvoker implements WorkflowClientCallsInterceptor {
  private static final Logger log = LoggerFactory.getLogger(RootWorkflowClientInvoker.class);
  private static final long POLL_UPDATE_TIMEOUT_S = 60L;

  private final GenericWorkflowClient genericClient;
  private final WorkflowClientOptions clientOptions;
  private final EagerWorkflowTaskDispatcher eagerWorkflowTaskDispatcher;
  private final WorkflowClientRequestFactory requestsHelper;

  public RootWorkflowClientInvoker(
      GenericWorkflowClient genericClient,
      WorkflowClientOptions clientOptions,
      WorkerFactoryRegistry workerFactoryRegistry) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
    this.eagerWorkflowTaskDispatcher = new EagerWorkflowTaskDispatcher(workerFactoryRegistry);
    this.requestsHelper = new WorkflowClientRequestFactory(clientOptions);
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowId()));

    StartWorkflowExecutionRequest.Builder startRequest =
        toStartRequest(dataConverterWithWorkflowContext, input);

    try (@Nullable WorkflowTaskDispatchHandle eagerDispatchHandle = obtainDispatchHandle(input)) {
      boolean requestEagerExecution = eagerDispatchHandle != null;
      startRequest.setRequestEagerExecution(requestEagerExecution);
      StartWorkflowExecutionResponse response = genericClient.start(startRequest.build());
      WorkflowExecution execution =
          WorkflowExecution.newBuilder()
              .setRunId(response.getRunId())
              .setWorkflowId(startRequest.getWorkflowId())
              .build();
      @Nullable
      PollWorkflowTaskQueueResponse eagerWorkflowTask =
          requestEagerExecution && response.hasEagerWorkflowTask()
              ? response.getEagerWorkflowTask()
              : null;
      if (eagerWorkflowTask != null) {
        try {
          eagerDispatchHandle.dispatch(eagerWorkflowTask);
        } catch (Exception e) {
          // Any exception here is not expected, and it's a bug.
          // But we don't allow any exception from the dispatching to disrupt the control flow here,
          // the Client needs to get the execution back to matter what.
          // Inability to dispatch a WFT creates a latency issue, but it's not a failure of the
          // start itself
          log.error(
              "[BUG] Eager Workflow Task was received from the Server, but failed to be dispatched on the local worker",
              e);
        }
      }
      if (CurrentNexusOperationContext.isNexusContext()) {
        CurrentNexusOperationContext.get().setStartWorkflowResponseLink(response.getLink());
      }
      return new WorkflowStartOutput(execution);
    }
  }

  @Override
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    SignalWorkflowExecutionRequest.Builder request =
        SignalWorkflowExecutionRequest.newBuilder()
            .setSignalName(input.getSignalName())
            .setWorkflowExecution(input.getWorkflowExecution())
            .setIdentity(clientOptions.getIdentity())
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(UUID.randomUUID().toString())
            .setHeader(HeaderUtils.toHeaderGrpc(input.getHeader(), null));

    DataConverter dataConverterWitSignalContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

    Optional<Payloads> inputArgs = dataConverterWitSignalContext.toPayloads(input.getArguments());
    inputArgs.ifPresent(request::setInput);
    genericClient.signal(request.build());
    return new WorkflowSignalOutput();
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    WorkflowStartInput workflowStartInput = input.getWorkflowStartInput();

    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), workflowStartInput.getWorkflowId()));
    StartWorkflowExecutionRequestOrBuilder startRequest =
        toStartRequest(dataConverterWithWorkflowContext, workflowStartInput);

    Optional<Payloads> signalInput =
        dataConverterWithWorkflowContext.toPayloads(input.getSignalArguments());
    SignalWithStartWorkflowExecutionRequest request =
        requestsHelper
            .newSignalWithStartWorkflowExecutionRequest(
                startRequest, input.getSignalName(), signalInput.orElse(null))
            .build();
    SignalWithStartWorkflowExecutionResponse response = genericClient.signalWithStart(request);
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setRunId(response.getRunId())
            .setWorkflowId(request.getWorkflowId())
            .build();
    // TODO currently SignalWithStartWorkflowExecutionResponse doesn't have eagerWorkflowTask.
    //  We should wire it when it's implemented server-side.
    return new WorkflowSignalWithStartOutput(new WorkflowStartOutput(execution));
  }

  @Override
  public <R> WorkflowUpdateWithStartOutput<R> updateWithStart(
      WorkflowUpdateWithStartInput<R> input) {

    WorkflowStartInput startInput = input.getWorkflowStartInput();
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), startInput.getWorkflowId()));

    ExecuteMultiOperationRequest request =
        ExecuteMultiOperationRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .addOperations(
                0,
                ExecuteMultiOperationRequest.Operation.newBuilder()
                    .setStartWorkflow(toStartRequest(dataConverterWithWorkflowContext, startInput))
                    .build())
            .addOperations(
                1,
                ExecuteMultiOperationRequest.Operation.newBuilder()
                    .setUpdateWorkflow(
                        toUpdateWorkflowExecutionRequest(
                            input.getStartUpdateInput(), dataConverterWithWorkflowContext)))
            .build();

    ExecuteMultiOperationResponse response;
    StartWorkflowExecutionResponse startResponse;
    UpdateWorkflowExecutionResponse updateResponse;

    do {
      try {
        Deadline pollTimeoutDeadline = Deadline.after(POLL_UPDATE_TIMEOUT_S, TimeUnit.SECONDS);
        response = genericClient.executeMultiOperation(request, pollTimeoutDeadline);

        if (response.getResponsesCount() != request.getOperationsCount()) {
          throw new RuntimeException(
              "Server sent back an invalid response: received "
                  + response.getResponsesCount()
                  + " instead of "
                  + request.getOperationsCount()
                  + " operation responses");
        }

        ExecuteMultiOperationResponse.Response firstResponse = response.getResponses(0);
        if (firstResponse.getResponseCase() != START_WORKFLOW) {
          throw new RuntimeException(
              "Server sent back an invalid response type for StartWorkflow response");
        }
        startResponse = firstResponse.getStartWorkflow();

        ExecuteMultiOperationResponse.Response secondResponse = response.getResponses(1);
        if (secondResponse.getResponseCase() != UPDATE_WORKFLOW) {
          throw new RuntimeException(
              "Server sent back an invalid response type for UpdateWorkflow response");
        }
        updateResponse = secondResponse.getUpdateWorkflow();
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
            || e.getStatus().getCode() == Status.Code.CANCELLED) {
          throw new WorkflowUpdateTimeoutOrCancelledException(
              input.getStartUpdateInput().getWorkflowExecution(),
              input.getStartUpdateInput().getUpdateName(),
              input.getStartUpdateInput().getUpdateId(),
              e);
        }

        MultiOperationExecutionFailure failure =
            StatusUtils.getFailure(e, MultiOperationExecutionFailure.class);
        if (failure == null) {
          throw e;
        }

        if (failure.getStatusesCount() != request.getOperationsCount()) {
          throw new RuntimeException(
              "Server sent back an invalid error response: received "
                  + failure.getStatusesCount()
                  + " instead of "
                  + request.getOperationsCount()
                  + " operation errors");
        }

        MultiOperationExecutionFailure.OperationStatus startStatus = failure.getStatuses(0);
        if (startStatus.getCode() != Status.Code.OK.value()
            && (startStatus.getDetailsCount() == 0
                || !startStatus.getDetails(0).is(MultiOperationExecutionAborted.class))) {
          throw Status.fromCodeValue(startStatus.getCode())
              .withDescription(startStatus.getMessage())
              .asRuntimeException();
        }

        MultiOperationExecutionFailure.OperationStatus updateStatus = failure.getStatuses(1);
        if (updateStatus.getCode() != Status.Code.OK.value()
            && (updateStatus.getDetailsCount() == 0
                || !updateStatus.getDetails(0).is(MultiOperationExecutionAborted.class))) {
          throw Status.fromCodeValue(updateStatus.getCode())
              .withDescription(updateStatus.getMessage())
              .asRuntimeException();
        }

        throw e; // no detailed failure was found
      }
    } while (updateNotYetDurable(input.getStartUpdateInput(), updateResponse));

    WorkflowUpdateHandle updateHandle =
        toUpdateHandle(
            input.getStartUpdateInput(), updateResponse, dataConverterWithWorkflowContext);

    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setRunId(startResponse.getRunId())
            .setWorkflowId(
                toStartRequest(dataConverterWithWorkflowContext, startInput)
                    .build()
                    .getWorkflowId())
            .build();
    return new WorkflowUpdateWithStartOutput<>(new WorkflowStartOutput(execution), updateHandle);
  }

  private StartWorkflowExecutionRequest.Builder toStartRequest(
      DataConverter dataConverterWithWorkflowContext, WorkflowStartInput workflowStartInput) {
    Optional<Payloads> workflowInput =
        dataConverterWithWorkflowContext.toPayloads(workflowStartInput.getArguments());

    @Nullable
    Memo memo =
        (workflowStartInput.getOptions().getMemo() != null)
            ? Memo.newBuilder()
                .putAllFields(
                    intoPayloadMap(
                        dataConverterWithWorkflowContext,
                        workflowStartInput.getOptions().getMemo()))
                .build()
            : null;

    @Nullable
    UserMetadata userMetadata =
        makeUserMetaData(
            workflowStartInput.getOptions().getStaticSummary(),
            workflowStartInput.getOptions().getStaticDetails(),
            dataConverterWithWorkflowContext);

    return requestsHelper.newStartWorkflowExecutionRequest(
        workflowStartInput.getWorkflowId(),
        workflowStartInput.getWorkflowType(),
        workflowStartInput.getHeader(),
        workflowStartInput.getOptions(),
        workflowInput.orElse(null),
        memo,
        userMetadata);
  }

  @Override
  public <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));
    Optional<Payloads> resultValue =
        WorkflowClientLongPollHelper.getWorkflowExecutionResult(
            genericClient,
            requestsHelper,
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            dataConverterWithWorkflowContext,
            input.getTimeout(),
            input.getTimeoutUnit());
    return new GetResultOutput<>(
        convertResultPayloads(
            resultValue,
            input.getResultClass(),
            input.getResultType(),
            dataConverterWithWorkflowContext));
  }

  @Override
  public <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input) {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));
    CompletableFuture<Optional<Payloads>> resultValue =
        WorkflowClientLongPollAsyncHelper.getWorkflowExecutionResultAsync(
            genericClient,
            requestsHelper,
            input.getWorkflowExecution(),
            input.getWorkflowType(),
            input.getTimeout(),
            input.getTimeoutUnit(),
            dataConverterWithWorkflowContext);
    return new GetResultAsyncOutput<>(
        resultValue.thenApply(
            payloads ->
                convertResultPayloads(
                    payloads,
                    input.getResultClass(),
                    input.getResultType(),
                    dataConverterWithWorkflowContext)));
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    WorkflowQuery.Builder query =
        WorkflowQuery.newBuilder()
            .setQueryType(input.getQueryType())
            .setHeader(HeaderUtils.toHeaderGrpc(input.getHeader(), null));
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

    Optional<Payloads> inputArgs =
        dataConverterWithWorkflowContext.toPayloads(input.getArguments());
    inputArgs.ifPresent(query::setQueryArgs);
    QueryWorkflowRequest request =
        QueryWorkflowRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(input.getWorkflowExecution().getWorkflowId())
                    .setRunId(input.getWorkflowExecution().getRunId()))
            .setQuery(query)
            .setQueryRejectCondition(clientOptions.getQueryRejectCondition())
            .build();

    QueryWorkflowResponse result;
    result = genericClient.query(request);

    boolean queryRejected = result.hasQueryRejected();
    WorkflowExecutionStatus rejectStatus =
        queryRejected ? result.getQueryRejected().getStatus() : null;
    Optional<Payloads> queryResult =
        result.hasQueryResult() ? Optional.of(result.getQueryResult()) : Optional.empty();
    R resultValue =
        convertResultPayloads(
            queryResult,
            input.getResultClass(),
            input.getResultType(),
            dataConverterWithWorkflowContext);
    return new QueryOutput<>(rejectStatus, resultValue);
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

    UpdateWorkflowExecutionRequest updateRequest =
        toUpdateWorkflowExecutionRequest(input, dataConverterWithWorkflowContext);

    // Re-attempt the update until it is at least accepted, or passes the lifecycle stage specified
    // by the user.
    UpdateWorkflowExecutionResponse result;
    do {
      Deadline pollTimeoutDeadline = Deadline.after(POLL_UPDATE_TIMEOUT_S, TimeUnit.SECONDS);
      try {
        result = genericClient.update(updateRequest, pollTimeoutDeadline);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
            || e.getStatus().getCode() == Status.Code.CANCELLED) {
          throw new WorkflowUpdateTimeoutOrCancelledException(
              input.getWorkflowExecution(), input.getUpdateName(), input.getUpdateId(), e);
        }
        throw e;
      }
    } while (updateNotYetDurable(input, result));

    return toUpdateHandle(input, result, dataConverterWithWorkflowContext);
  }

  private <R> boolean updateNotYetDurable(
      StartUpdateInput<R> input, UpdateWorkflowExecutionResponse result) {
    return result.getStage().getNumber() < input.getWaitPolicy().getLifecycleStage().getNumber()
        && result.getStage().getNumber()
            < UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED
                .getNumber();
  }

  private <R> UpdateWorkflowExecutionRequest toUpdateWorkflowExecutionRequest(
      StartUpdateInput<R> input, DataConverter dataConverterWithWorkflowContext) {
    Optional<Payloads> inputArgs =
        dataConverterWithWorkflowContext.toPayloads(input.getArguments());
    Input.Builder updateInput =
        Input.newBuilder()
            .setHeader(HeaderUtils.toHeaderGrpc(input.getHeader(), null))
            .setName(input.getUpdateName());
    inputArgs.ifPresent(updateInput::setArgs);

    Request request =
        Request.newBuilder()
            .setMeta(
                Meta.newBuilder()
                    .setUpdateId(input.getUpdateId())
                    .setIdentity(clientOptions.getIdentity()))
            .setInput(updateInput)
            .build();

    return UpdateWorkflowExecutionRequest.newBuilder()
        .setNamespace(clientOptions.getNamespace())
        .setWaitPolicy(input.getWaitPolicy())
        .setWorkflowExecution(
            WorkflowExecution.newBuilder()
                .setWorkflowId(input.getWorkflowExecution().getWorkflowId())
                .setRunId(input.getWorkflowExecution().getRunId()))
        .setFirstExecutionRunId(input.getFirstExecutionRunId())
        .setRequest(request)
        .build();
  }

  private <R> WorkflowUpdateHandle<R> toUpdateHandle(
      StartUpdateInput<R> input,
      UpdateWorkflowExecutionResponse result,
      DataConverter dataConverterWithWorkflowContext) {
    if (result.hasOutcome()) {
      switch (result.getOutcome().getValueCase()) {
        case SUCCESS:
          Optional<Payloads> updateResult = Optional.of(result.getOutcome().getSuccess());
          R resultValue =
              convertResultPayloads(
                  updateResult,
                  input.getResultClass(),
                  input.getResultType(),
                  dataConverterWithWorkflowContext);
          return new CompletedWorkflowUpdateHandleImpl<>(
              result.getUpdateRef().getUpdateId(),
              result.getUpdateRef().getWorkflowExecution(),
              resultValue);
        case FAILURE:
          return new CompletedWorkflowUpdateHandleImpl<>(
              result.getUpdateRef().getUpdateId(),
              result.getUpdateRef().getWorkflowExecution(),
              new WorkflowUpdateException(
                  result.getUpdateRef().getWorkflowExecution(),
                  result.getUpdateRef().getUpdateId(),
                  input.getUpdateName(),
                  dataConverterWithWorkflowContext.failureToException(
                      result.getOutcome().getFailure())));
        default:
          throw new RuntimeException(
              "Received unexpected outcome from update request: "
                  + result.getOutcome().getValueCase());
      }
    } else {
      LazyWorkflowUpdateHandleImpl<R> handle =
          new LazyWorkflowUpdateHandleImpl<>(
              this,
              input.getWorkflowType().orElse(null),
              input.getUpdateName(),
              result.getUpdateRef().getUpdateId(),
              result.getUpdateRef().getWorkflowExecution(),
              input.getResultClass(),
              input.getResultType());
      UpdateWorkflowExecutionLifecycleStage waitForStage =
          input.getWaitPolicy().getLifecycleStage();
      if (waitForStage == WorkflowUpdateStage.COMPLETED.getProto()) {
        // Don't return the handle until completed, since that's what's been asked for
        handle.waitCompleted();
      }
      return handle;
    }
  }

  @Override
  public <R> PollWorkflowUpdateOutput<R> pollWorkflowUpdate(PollWorkflowUpdateInput<R> input) {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

    UpdateRef update =
        UpdateRef.newBuilder()
            .setWorkflowExecution(input.getWorkflowExecution())
            .setUpdateId(input.getUpdateId())
            .build();

    WaitPolicy waitPolicy =
        WaitPolicy.newBuilder()
            .setLifecycleStage(
                UpdateWorkflowExecutionLifecycleStage
                    .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)
            .build();

    PollWorkflowExecutionUpdateRequest pollUpdateRequest =
        PollWorkflowExecutionUpdateRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setUpdateRef(update)
            .setWaitPolicy(waitPolicy)
            .build();

    CompletableFuture<PollWorkflowExecutionUpdateResponse> future = new CompletableFuture<>();

    Deadline pollTimeoutDeadline = Deadline.after(input.getTimeout(), input.getTimeoutUnit());
    pollWorkflowUpdateHelper(future, pollUpdateRequest, pollTimeoutDeadline);
    return new PollWorkflowUpdateOutput<>(
        future.thenApply(
            (result) -> {
              if (result.hasOutcome()) {
                switch (result.getOutcome().getValueCase()) {
                  case SUCCESS:
                    Optional<Payloads> updateResult = Optional.of(result.getOutcome().getSuccess());
                    return convertResultPayloads(
                        updateResult,
                        input.getResultClass(),
                        input.getResultType(),
                        dataConverterWithWorkflowContext);
                  case FAILURE:
                    throw new WorkflowUpdateException(
                        input.getWorkflowExecution(),
                        input.getUpdateId(),
                        input.getUpdateName(),
                        dataConverterWithWorkflowContext.failureToException(
                            result.getOutcome().getFailure()));
                  default:
                    throw new RuntimeException(
                        "Received unexpected outcome from poll update request: "
                            + result.getOutcome().getValueCase());
                }
              }
              throw new RuntimeException("Received no outcome from server");
            }));
  }

  private void pollWorkflowUpdateHelper(
      CompletableFuture<PollWorkflowExecutionUpdateResponse> resultCF,
      PollWorkflowExecutionUpdateRequest request,
      Deadline deadline) {
    genericClient
        .pollUpdateAsync(request, deadline)
        .whenComplete(
            (r, e) -> {
              if (e == null && !r.hasOutcome()) {
                pollWorkflowUpdateHelper(resultCF, request, deadline);
                return;
              }
              if ((e instanceof StatusRuntimeException
                      && (((StatusRuntimeException) e).getStatus().getCode()
                              == Status.Code.DEADLINE_EXCEEDED
                          || ((StatusRuntimeException) e).getStatus().getCode()
                              == Status.Code.CANCELLED))
                  || deadline.isExpired()) {
                resultCF.completeExceptionally(
                    new WorkflowUpdateTimeoutOrCancelledException(
                        request.getUpdateRef().getWorkflowExecution(),
                        request.getUpdateRef().getUpdateId(),
                        "",
                        e));
              } else if (e != null) {
                resultCF.completeExceptionally(e);
              } else {
                resultCF.complete(r);
              }
            });
  }

  @Override
  public CancelOutput cancel(CancelInput input) {
    RequestCancelWorkflowExecutionRequest.Builder request =
        RequestCancelWorkflowExecutionRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setWorkflowExecution(input.getWorkflowExecution())
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity());
    if (input.getReason() != null) {
      request.setReason(input.getReason());
    }
    genericClient.requestCancel(request.build());
    return new CancelOutput();
  }

  @Override
  public TerminateOutput terminate(TerminateInput input) {
    TerminateWorkflowExecutionRequest.Builder request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setWorkflowExecution(input.getWorkflowExecution());
    if (input.getReason() != null) {
      request.setReason(input.getReason());
    }
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));
    Optional<Payloads> payloads = dataConverterWithWorkflowContext.toPayloads(input.getDetails());
    payloads.ifPresent(request::setDetails);
    genericClient.terminate(request.build());
    return new TerminateOutput();
  }

  @Override
  public DescribeWorkflowOutput describe(DescribeWorkflowInput input) {
    DescribeWorkflowExecutionResponse response =
        genericClient.describeWorkflowExecution(
            DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(clientOptions.getNamespace())
                .setExecution(input.getWorkflowExecution())
                .build());

    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

    return new DescribeWorkflowOutput(
        new WorkflowExecutionDescription(response, dataConverterWithWorkflowContext));
  }

  @Override
  public CountWorkflowOutput countWorkflows(CountWorkflowsInput input) {
    CountWorkflowExecutionsRequest.Builder req =
        CountWorkflowExecutionsRequest.newBuilder().setNamespace(clientOptions.getNamespace());
    if (input.getQuery() != null) {
      req.setQuery(input.getQuery());
    }
    CountWorkflowExecutionsResponse resp = genericClient.countWorkflowExecutions(req.build());
    return new CountWorkflowOutput(new WorkflowExecutionCount(resp));
  }

  @Override
  public ListWorkflowExecutionsOutput listWorkflowExecutions(ListWorkflowExecutionsInput input) {
    ListWorkflowExecutionIterator iterator =
        new ListWorkflowExecutionIterator(
            input.getQuery(), clientOptions.getNamespace(), input.getPageSize(), genericClient);
    iterator.init();
    Iterator<WorkflowExecutionMetadata> wrappedIterator =
        Iterators.transform(
            iterator,
            info -> new WorkflowExecutionMetadata(info, clientOptions.getDataConverter()));

    // IMMUTABLE here means that "interference" (in Java Streams terms) to this spliterator is
    // impossible
    //  TODO We don't add DISTINCT to be safe. It's not explicitly stated if Temporal Server list
    // API
    // guarantees absence of duplicates
    final int CHARACTERISTICS = Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;

    return new ListWorkflowExecutionsOutput(
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(wrappedIterator, CHARACTERISTICS), false));
  }

  private static <R> R convertResultPayloads(
      Optional<Payloads> resultValue,
      Class<R> resultClass,
      Type resultType,
      DataConverter dataConverter) {
    return dataConverter.fromPayloads(0, resultValue, resultClass, resultType);
  }

  /**
   * @return a handle to dispatch the eager workflow task. {@code null} if an eager execution is
   *     disabled through {@link io.temporal.client.WorkflowOptions} or the worker
   *     <ul>
   *       <li>is activity only worker
   *       <li>not started, shutdown or paused
   *       <li>doesn't have an executor slot available
   *     </ul>
   */
  @Nullable
  private WorkflowTaskDispatchHandle obtainDispatchHandle(WorkflowStartInput input) {
    if (input.getOptions().isDisableEagerExecution()) {
      return null;
    }
    return eagerWorkflowTaskDispatcher.tryGetLocalDispatchHandler(input);
  }
}
