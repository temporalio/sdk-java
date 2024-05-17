/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.client;

import static io.temporal.internal.common.HeaderUtils.intoPayloadMap;

import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.update.v1.*;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.worker.WorkflowTaskDispatchHandle;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    Optional<Payloads> inputArgs =
        dataConverterWithWorkflowContext.toPayloads(input.getArguments());

    @Nullable
    Memo memo =
        (input.getOptions().getMemo() != null)
            ? Memo.newBuilder()
                .putAllFields(
                    intoPayloadMap(dataConverterWithWorkflowContext, input.getOptions().getMemo()))
                .build()
            : null;

    StartWorkflowExecutionRequest.Builder request =
        requestsHelper.newStartWorkflowExecutionRequest(
            input.getWorkflowId(),
            input.getWorkflowType(),
            input.getHeader(),
            input.getOptions(),
            inputArgs.orElse(null),
            memo);
    try (@Nullable WorkflowTaskDispatchHandle eagerDispatchHandle = obtainDispatchHandle(input)) {
      boolean requestEagerExecution = eagerDispatchHandle != null;
      request.setRequestEagerExecution(requestEagerExecution);
      StartWorkflowExecutionResponse response = genericClient.start(request.build());
      WorkflowExecution execution =
          WorkflowExecution.newBuilder()
              .setRunId(response.getRunId())
              .setWorkflowId(request.getWorkflowId())
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

    StartWorkflowExecutionRequestOrBuilder startRequest =
        requestsHelper.newStartWorkflowExecutionRequest(
            workflowStartInput.getWorkflowId(),
            workflowStartInput.getWorkflowType(),
            workflowStartInput.getHeader(),
            workflowStartInput.getOptions(),
            workflowInput.orElse(null),
            memo);

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
  public <R> StartUpdateOutput<R> startUpdate(StartUpdateInput<R> input) {
    DataConverter dataConverterWithWorkflowContext =
        clientOptions
            .getDataConverter()
            .withContext(
                new WorkflowSerializationContext(
                    clientOptions.getNamespace(), input.getWorkflowExecution().getWorkflowId()));

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
    UpdateWorkflowExecutionRequest updateRequest =
        UpdateWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setWaitPolicy(input.getWaitPolicy())
            .setWorkflowExecution(
                WorkflowExecution.newBuilder()
                    .setWorkflowId(input.getWorkflowExecution().getWorkflowId())
                    .setRunId(input.getWorkflowExecution().getRunId()))
            .setFirstExecutionRunId(input.getFirstExecutionRunId())
            .setRequest(request)
            .build();
    Deadline pollTimeoutDeadline = Deadline.after(POLL_UPDATE_TIMEOUT_S, TimeUnit.SECONDS);

    // Re-attempt the update until it is at least accepted, or passes the lifecycle stage specified
    // by the user.
    UpdateWorkflowExecutionResponse result;
    do {
      result = genericClient.update(updateRequest, pollTimeoutDeadline);
    } while (result.getStage().getNumber() < input.getWaitPolicy().getLifecycleStage().getNumber()
        && result.getStage().getNumber()
            < UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED
                .getNumber());

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
          return new StartUpdateOutput<R>(result.getUpdateRef(), true, resultValue);
        case FAILURE:
          throw new WorkflowUpdateException(
              result.getUpdateRef().getWorkflowExecution(),
              result.getUpdateRef().getUpdateId(),
              input.getUpdateName(),
              dataConverterWithWorkflowContext.failureToException(
                  result.getOutcome().getFailure()));
        default:
          throw new RuntimeException(
              "Received unexpected outcome from update request: "
                  + result.getOutcome().getValueCase());
      }
    } else {
      return new StartUpdateOutput<R>(result.getUpdateRef(), false, null);
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
              if ((e instanceof StatusRuntimeException
                      && ((StatusRuntimeException) e).getStatus().getCode()
                          == Status.Code.DEADLINE_EXCEEDED)
                  || deadline.isExpired()
                  || (e == null && !r.hasOutcome())) {
                resultCF.completeExceptionally(
                    new TimeoutException(
                        "WorkflowId="
                            + request.getUpdateRef().getWorkflowExecution().getWorkflowId()
                            + ", runId="
                            + request.getUpdateRef().getWorkflowExecution().getRunId()
                            + ", updateId="
                            + request.getUpdateRef().getUpdateId()));
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
