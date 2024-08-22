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

package io.temporal.internal.sync;

import static io.temporal.internal.common.HeaderUtils.intoPayloadMap;
import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;
import static io.temporal.internal.sync.WorkflowInternal.DEFAULT_VERSION;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.*;
import io.temporal.internal.common.ActivityOptionUtils;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.replay.ChildWorkflowTaskFailedException;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.WorkflowContext;
import io.temporal.internal.statemachines.*;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO separate WorkflowOutboundCallsInterceptor functionality from this class into
// RootWorkflowOutboundInterceptor

/**
 * Root, the most top level WorkflowContext that unites all relevant contexts, handlers, options,
 * states, etc. It's created when SyncWorkflow which represent a context of a workflow type
 * definition on the worker meets ReplayWorkflowContext which contains information from the
 * WorkflowTask
 */
final class SyncWorkflowContext implements WorkflowContext, WorkflowOutboundCallsInterceptor {
  private static final Logger log = LoggerFactory.getLogger(SyncWorkflowContext.class);

  private final String namespace;
  private final WorkflowExecution workflowExecution;
  private final WorkflowImplementationOptions workflowImplementationOptions;
  private final DataConverter dataConverter;
  // to be used in this class, should not be passed down. Pass the original #dataConverter instead
  private final DataConverter dataConverterWithCurrentWorkflowContext;
  private final List<ContextPropagator> contextPropagators;
  private final SignalDispatcher signalDispatcher;
  private final QueryDispatcher queryDispatcher;
  private final UpdateDispatcher updateDispatcher;

  // initialized later when these entities are created
  private ReplayWorkflowContext replayContext;
  private DeterministicRunner runner;

  private WorkflowInboundCallsInterceptor headInboundInterceptor;
  private WorkflowOutboundCallsInterceptor headOutboundInterceptor;

  private ActivityOptions defaultActivityOptions = null;
  private Map<String, ActivityOptions> activityOptionsMap;
  private LocalActivityOptions defaultLocalActivityOptions = null;
  private Map<String, LocalActivityOptions> localActivityOptionsMap;
  private NexusServiceOptions defaultNexusServiceOptions = null;
  private Map<String, NexusServiceOptions> nexusServiceOptionsMap;
  private boolean readOnly = false;
  private final WorkflowThreadLocal<UpdateInfo> currentUpdateInfo = new WorkflowThreadLocal<>();
  // Map of all running update handlers. Key is the update Id of the update request.
  private Map<String, UpdateHandlerInfo> runningUpdateHandlers = new HashMap<>();
  // Map of all running signal handlers. Key is the event Id of the signal event.
  private Map<Long, SignalHandlerInfo> runningSignalHandlers = new HashMap<>();

  public SyncWorkflowContext(
      @Nonnull String namespace,
      @Nonnull WorkflowExecution workflowExecution,
      SignalDispatcher signalDispatcher,
      QueryDispatcher queryDispatcher,
      UpdateDispatcher updateDispatcher,
      @Nullable WorkflowImplementationOptions workflowImplementationOptions,
      DataConverter dataConverter,
      List<ContextPropagator> contextPropagators) {
    this.namespace = namespace;
    this.workflowExecution = workflowExecution;
    this.dataConverter = dataConverter;
    this.dataConverterWithCurrentWorkflowContext =
        dataConverter.withContext(
            new WorkflowSerializationContext(namespace, workflowExecution.getWorkflowId()));
    this.contextPropagators = contextPropagators;
    this.signalDispatcher = signalDispatcher;
    this.queryDispatcher = queryDispatcher;
    this.updateDispatcher = updateDispatcher;
    if (workflowImplementationOptions != null) {
      this.defaultActivityOptions = workflowImplementationOptions.getDefaultActivityOptions();
      this.activityOptionsMap = new HashMap<>(workflowImplementationOptions.getActivityOptions());
      this.defaultLocalActivityOptions =
          workflowImplementationOptions.getDefaultLocalActivityOptions();
      this.localActivityOptionsMap =
          new HashMap<>(workflowImplementationOptions.getLocalActivityOptions());
      this.defaultNexusServiceOptions =
          workflowImplementationOptions.getDefaultNexusServiceOptions();
      this.nexusServiceOptionsMap =
          new HashMap<>(workflowImplementationOptions.getNexusServiceOptions());
    }
    this.workflowImplementationOptions =
        workflowImplementationOptions == null
            ? WorkflowImplementationOptions.getDefaultInstance()
            : workflowImplementationOptions;
    // initial values for headInboundInterceptor and headOutboundInterceptor until they initialized
    // with actual interceptors through #initHeadInboundCallsInterceptor and
    // #initHeadOutboundCallsInterceptor during initialization phase.
    // See workflow.initialize() performed inside the workflow root thread inside
    // SyncWorkflow#start(HistoryEvent, ReplayWorkflowContext)
    this.headInboundInterceptor = new InitialWorkflowInboundCallsInterceptor(this);
    this.headOutboundInterceptor = this;
  }

  public void setReplayContext(ReplayWorkflowContext context) {
    this.replayContext = context;
  }

  /**
   * Using setter, as runner is initialized with this context, so it is not ready during
   * construction of this.
   */
  public void setRunner(DeterministicRunner runner) {
    this.runner = runner;
  }

  public DeterministicRunner getRunner() {
    return runner;
  }

  public WorkflowOutboundCallsInterceptor getWorkflowOutboundInterceptor() {
    return headOutboundInterceptor;
  }

  public WorkflowInboundCallsInterceptor getWorkflowInboundInterceptor() {
    return headInboundInterceptor;
  }

  public void initHeadOutboundCallsInterceptor(WorkflowOutboundCallsInterceptor head) {
    headOutboundInterceptor = head;
  }

  public void initHeadInboundCallsInterceptor(WorkflowInboundCallsInterceptor head) {
    headInboundInterceptor = head;
    signalDispatcher.setInboundCallsInterceptor(head);
    queryDispatcher.setInboundCallsInterceptor(head);
    updateDispatcher.setInboundCallsInterceptor(head);
  }

  public ActivityOptions getDefaultActivityOptions() {
    return defaultActivityOptions;
  }

  public @Nonnull Map<String, ActivityOptions> getActivityOptions() {
    return activityOptionsMap != null
        ? Collections.unmodifiableMap(activityOptionsMap)
        : Collections.emptyMap();
  }

  public LocalActivityOptions getDefaultLocalActivityOptions() {
    return defaultLocalActivityOptions;
  }

  public @Nonnull Map<String, LocalActivityOptions> getLocalActivityOptions() {
    return localActivityOptionsMap != null
        ? Collections.unmodifiableMap(localActivityOptionsMap)
        : Collections.emptyMap();
  }

  public NexusServiceOptions getDefaultNexusServiceOptions() {
    return defaultNexusServiceOptions;
  }

  public @Nonnull Map<String, NexusServiceOptions> getNexusServiceOptions() {
    return nexusServiceOptionsMap != null
        ? Collections.unmodifiableMap(nexusServiceOptionsMap)
        : Collections.emptyMap();
  }

  public void setDefaultActivityOptions(ActivityOptions defaultActivityOptions) {
    this.defaultActivityOptions =
        (this.defaultActivityOptions == null)
            ? defaultActivityOptions
            : this.defaultActivityOptions.toBuilder()
                .mergeActivityOptions(defaultActivityOptions)
                .build();
  }

  public void applyActivityOptions(Map<String, ActivityOptions> activityTypeToOption) {
    Objects.requireNonNull(activityTypeToOption);
    if (this.activityOptionsMap == null) {
      this.activityOptionsMap = new HashMap<>(activityTypeToOption);
      return;
    }
    ActivityOptionUtils.mergePredefinedActivityOptions(activityOptionsMap, activityTypeToOption);
  }

  public void setDefaultLocalActivityOptions(LocalActivityOptions defaultLocalActivityOptions) {
    this.defaultLocalActivityOptions =
        (this.defaultLocalActivityOptions == null)
            ? defaultLocalActivityOptions
            : this.defaultLocalActivityOptions.toBuilder()
                .mergeActivityOptions(defaultLocalActivityOptions)
                .build();
  }

  public void applyLocalActivityOptions(Map<String, LocalActivityOptions> activityTypeToOption) {
    Objects.requireNonNull(activityTypeToOption);
    if (this.localActivityOptionsMap == null) {
      this.localActivityOptionsMap = new HashMap<>(activityTypeToOption);
      return;
    }
    ActivityOptionUtils.mergePredefinedLocalActivityOptions(
        localActivityOptionsMap, activityTypeToOption);
  }

  @Override
  public <T> ActivityOutput<T> executeActivity(ActivityInput<T> input) {
    ActivitySerializationContext serializationContext =
        new ActivitySerializationContext(
            replayContext.getNamespace(),
            replayContext.getWorkflowId(),
            replayContext.getWorkflowType().getName(),
            input.getActivityName(),
            // input.getOptions().getTaskQueue() may be not specified, workflow task queue is used
            // by the Server in this case
            MoreObjects.firstNonNull(
                input.getOptions().getTaskQueue(), replayContext.getTaskQueue()),
            false);
    DataConverter dataConverterWithActivityContext =
        dataConverter.withContext(serializationContext);
    Optional<Payloads> args = dataConverterWithActivityContext.toPayloads(input.getArgs());

    ActivityOutput<Optional<Payloads>> output =
        executeActivityOnce(input.getActivityName(), input.getOptions(), input.getHeader(), args);

    return new ActivityOutput<>(
        output.getActivityId(),
        output
            .getResult()
            .handle(
                (r, f) -> {
                  if (f == null) {
                    return input.getResultType() != Void.TYPE
                        ? dataConverterWithActivityContext.fromPayloads(
                            0, r, input.getResultClass(), input.getResultType())
                        : null;
                  } else {
                    throw dataConverterWithActivityContext.failureToException(
                        ((FailureWrapperException) f).getFailure());
                  }
                }));
  }

  private ActivityOutput<Optional<Payloads>> executeActivityOnce(
      String activityTypeName, ActivityOptions options, Header header, Optional<Payloads> input) {
    ExecuteActivityParameters params =
        constructExecuteActivityParameters(activityTypeName, options, header, input);
    ActivityCallback callback = new ActivityCallback();
    ReplayWorkflowContext.ScheduleActivityTaskOutput activityOutput =
        replayContext.scheduleActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              activityOutput.getCancellationHandle().apply(new CanceledFailure(reason));
              return null;
            });
    return new ActivityOutput<>(activityOutput.getActivityId(), callback.result);
  }

  public void handleInterceptedSignal(WorkflowInboundCallsInterceptor.SignalInput input) {
    signalDispatcher.handleInterceptedSignal(input);
  }

  public void handleSignal(
      String signalName, Optional<Payloads> input, long eventId, Header header) {
    signalDispatcher.handleSignal(signalName, input, eventId, header);
  }

  public void handleValidateUpdate(
      String updateName, String updateId, Optional<Payloads> input, long eventId, Header header) {
    updateDispatcher.handleValidateUpdate(updateName, updateId, input, eventId, header);
  }

  public Optional<Payloads> handleExecuteUpdate(
      String updateName, String updateId, Optional<Payloads> input, long eventId, Header header) {
    return updateDispatcher.handleExecuteUpdate(updateName, updateId, input, eventId, header);
  }

  public void handleInterceptedValidateUpdate(WorkflowInboundCallsInterceptor.UpdateInput input) {
    updateDispatcher.handleInterceptedValidateUpdate(input);
  }

  public WorkflowInboundCallsInterceptor.UpdateOutput handleInterceptedExecuteUpdate(
      WorkflowInboundCallsInterceptor.UpdateInput input) {
    return updateDispatcher.handleInterceptedExecuteUpdate(input);
  }

  public WorkflowInboundCallsInterceptor.QueryOutput handleInterceptedQuery(
      WorkflowInboundCallsInterceptor.QueryInput input) {
    return queryDispatcher.handleInterceptedQuery(input);
  }

  public Optional<Payloads> handleQuery(String queryName, Header header, Optional<Payloads> input) {
    return queryDispatcher.handleQuery(queryName, header, input);
  }

  public boolean isEveryHandlerFinished() {
    return updateDispatcher.getRunningUpdateHandlers().isEmpty()
        && signalDispatcher.getRunningSignalHandlers().isEmpty();
  }

  private class ActivityCallback {
    private final CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();

    public void invoke(Optional<Payloads> output, Failure failure) {
      if (failure != null) {
        runner.executeInWorkflowThread(
            "activity failure callback",
            () -> result.completeExceptionally(new FailureWrapperException(failure)));
      } else {
        runner.executeInWorkflowThread(
            "activity completion callback", () -> result.complete(output));
      }
    }
  }

  private class LocalActivityCallbackImpl implements LocalActivityCallback {
    private final CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();

    @Override
    public void apply(Optional<Payloads> successOutput, LocalActivityFailedException exception) {
      if (exception != null) {
        runner.executeInWorkflowThread(
            "local activity failure callback", () -> result.completeExceptionally(exception));
      } else {
        runner.executeInWorkflowThread(
            "local activity completion callback", () -> result.complete(successOutput));
      }
    }
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    ActivitySerializationContext serializationContext =
        new ActivitySerializationContext(
            replayContext.getNamespace(),
            replayContext.getWorkflowId(),
            replayContext.getWorkflowType().getName(),
            input.getActivityName(),
            replayContext.getTaskQueue(),
            true);
    DataConverter dataConverterWithActivityContext =
        dataConverter.withContext(serializationContext);
    Optional<Payloads> payloads = dataConverterWithActivityContext.toPayloads(input.getArgs());

    long originalScheduledTime = System.currentTimeMillis();
    CompletablePromise<Optional<Payloads>> serializedResult =
        WorkflowInternal.newCompletablePromise();
    executeLocalActivityOverLocalRetryThreshold(
        input.getActivityName(),
        input.getOptions(),
        input.getHeader(),
        payloads,
        originalScheduledTime,
        1,
        null,
        serializedResult);

    Promise<R> result =
        serializedResult.handle(
            (r, f) -> {
              if (f == null) {
                return input.getResultClass() != Void.TYPE
                    ? dataConverterWithActivityContext.fromPayloads(
                        0, r, input.getResultClass(), input.getResultType())
                    : null;
              } else {
                throw dataConverterWithActivityContext.failureToException(
                    ((LocalActivityCallback.LocalActivityFailedException) f).getFailure());
              }
            });

    return new LocalActivityOutput<>(result);
  }

  public void executeLocalActivityOverLocalRetryThreshold(
      String activityTypeName,
      LocalActivityOptions options,
      Header header,
      Optional<Payloads> input,
      long originalScheduledTime,
      int attempt,
      @Nullable Failure previousExecutionFailure,
      CompletablePromise<Optional<Payloads>> result) {
    CompletablePromise<Optional<Payloads>> localExecutionResult =
        executeLocalActivityLocally(
            activityTypeName,
            options,
            header,
            input,
            originalScheduledTime,
            attempt,
            previousExecutionFailure);

    localExecutionResult.handle(
        (r, e) -> {
          if (e == null) {
            result.complete(r);
          } else {
            if ((e instanceof LocalActivityCallback.LocalActivityFailedException)) {
              LocalActivityCallback.LocalActivityFailedException laException =
                  (LocalActivityCallback.LocalActivityFailedException) e;
              @Nullable Duration backoff = laException.getBackoff();
              if (backoff != null) {
                WorkflowInternal.newTimer(backoff)
                    .thenApply(
                        unused -> {
                          executeLocalActivityOverLocalRetryThreshold(
                              activityTypeName,
                              options,
                              header,
                              input,
                              originalScheduledTime,
                              laException.getLastAttempt() + 1,
                              laException.getFailure(),
                              result);
                          return null;
                        });
              } else {
                // final failure, report back
                result.completeExceptionally(laException);
              }
            } else {
              // Only LocalActivityFailedException is expected
              String exceptionMessage =
                  String.format(
                      "[BUG] Local Activity State Machine callback for activityType %s returned unexpected exception",
                      activityTypeName);
              log.warn(exceptionMessage, e);
              replayContext.failWorkflowTask(new IllegalStateException(exceptionMessage, e));
            }
          }
          return null;
        });
  }

  private CompletablePromise<Optional<Payloads>> executeLocalActivityLocally(
      String activityTypeName,
      LocalActivityOptions options,
      Header header,
      Optional<Payloads> input,
      long originalScheduledTime,
      int attempt,
      @Nullable Failure previousExecutionFailure) {

    LocalActivityCallbackImpl callback = new LocalActivityCallbackImpl();
    ExecuteLocalActivityParameters params =
        constructExecuteLocalActivityParameters(
            activityTypeName,
            options,
            header,
            input,
            attempt,
            originalScheduledTime,
            previousExecutionFailure);
    Functions.Proc cancellationCallback = replayContext.scheduleLocalActivityTask(params, callback);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.apply();
              return null;
            });
    return callback.result;
  }

  private ExecuteActivityParameters constructExecuteActivityParameters(
      String name, ActivityOptions options, Header header, Optional<Payloads> input) {
    String taskQueue = options.getTaskQueue();
    if (taskQueue == null) {
      taskQueue = replayContext.getTaskQueue();
    }
    ScheduleActivityTaskCommandAttributes.Builder attributes =
        ScheduleActivityTaskCommandAttributes.newBuilder()
            .setActivityType(ActivityType.newBuilder().setName(name))
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue))
            .setScheduleToStartTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getScheduleToStartTimeout()))
            .setStartToCloseTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()))
            .setScheduleToCloseTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()))
            .setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration(options.getHeartbeatTimeout()))
            .setRequestEagerExecution(
                !options.isEagerExecutionDisabled()
                    && Objects.equals(taskQueue, replayContext.getTaskQueue()));

    input.ifPresent(attributes::setInput);
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      attributes.setRetryPolicy(toRetryPolicy(retryOptions));
    }

    // Set the context value.  Use the context propagators from the ActivityOptions
    // if present, otherwise use the ones configured on the WorkflowContext
    List<ContextPropagator> propagators = options.getContextPropagators();
    if (propagators == null) {
      propagators = this.contextPropagators;
    }
    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(propagators));
    attributes.setHeader(grpcHeader);

    if (options.getVersioningIntent() != null) {
      attributes.setUseWorkflowBuildId(
          options
              .getVersioningIntent()
              .determineUseCompatibleFlag(
                  replayContext.getTaskQueue().equals(options.getTaskQueue())));
    }

    return new ExecuteActivityParameters(attributes, options.getCancellationType());
  }

  private ExecuteLocalActivityParameters constructExecuteLocalActivityParameters(
      String name,
      LocalActivityOptions options,
      Header header,
      Optional<Payloads> input,
      int attempt,
      long originalScheduledTime,
      @Nullable Failure previousExecutionFailure) {
    options = LocalActivityOptions.newBuilder(options).validateAndBuildWithDefaults();

    PollActivityTaskQueueResponse.Builder activityTask =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId(this.replayContext.randomUUID().toString())
            .setWorkflowNamespace(this.replayContext.getNamespace())
            .setWorkflowType(this.replayContext.getWorkflowType())
            .setWorkflowExecution(this.replayContext.getWorkflowExecution())
            // used to pass scheduled time to the local activity code inside
            // ActivityExecutionContext#getInfo
            // setCurrentAttemptScheduledTime is called inside LocalActivityWorker before submitting
            // into the LA queue
            .setScheduledTime(
                ProtobufTimeUtils.toProtoTimestamp(Instant.ofEpochMilli(originalScheduledTime)))
            .setActivityType(ActivityType.newBuilder().setName(name))
            .setAttempt(attempt);

    Duration scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
    if (scheduleToCloseTimeout != null) {
      activityTask.setScheduleToCloseTimeout(
          ProtobufTimeUtils.toProtoDuration(scheduleToCloseTimeout));
    }

    Duration startToCloseTimeout = options.getStartToCloseTimeout();
    if (startToCloseTimeout != null) {
      activityTask.setStartToCloseTimeout(ProtobufTimeUtils.toProtoDuration(startToCloseTimeout));
    }

    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(contextPropagators));
    activityTask.setHeader(grpcHeader);
    input.ifPresent(activityTask::setInput);
    RetryOptions retryOptions = options.getRetryOptions();
    activityTask.setRetryPolicy(
        toRetryPolicy(RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults()));
    Duration localRetryThreshold = options.getLocalRetryThreshold();
    if (localRetryThreshold == null) {
      localRetryThreshold = replayContext.getWorkflowTaskTimeout().multipliedBy(3);
    }

    return new ExecuteLocalActivityParameters(
        activityTask,
        options.getScheduleToStartTimeout(),
        originalScheduledTime,
        previousExecutionFailure,
        options.isDoNotIncludeArgumentsIntoMarker(),
        localRetryThreshold);
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    if (CancellationScope.current().isCancelRequested()) {
      CanceledFailure canceledFailure = new CanceledFailure("execute called from a canceled scope");
      return new ChildWorkflowOutput<>(
          Workflow.newFailedPromise(canceledFailure), Workflow.newFailedPromise(canceledFailure));
    }

    CompletablePromise<WorkflowExecution> executionPromise = Workflow.newPromise();
    CompletablePromise<Optional<Payloads>> resultPromise = Workflow.newPromise();

    DataConverter dataConverterWithChildWorkflowContext =
        dataConverter.withContext(
            new WorkflowSerializationContext(replayContext.getNamespace(), input.getWorkflowId()));
    Optional<Payloads> payloads = dataConverterWithChildWorkflowContext.toPayloads(input.getArgs());

    @Nullable
    Memo memo =
        (input.getOptions().getMemo() != null)
            ? Memo.newBuilder()
                .putAllFields(
                    intoPayloadMap(
                        dataConverterWithChildWorkflowContext, input.getOptions().getMemo()))
                .build()
            : null;

    StartChildWorkflowExecutionParameters parameters =
        createChildWorkflowParameters(
            input.getWorkflowId(),
            input.getWorkflowType(),
            input.getOptions(),
            input.getHeader(),
            payloads,
            memo);

    Functions.Proc1<Exception> cancellationCallback =
        replayContext.startChildWorkflow(
            parameters,
            (execution, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow start failed callback",
                    () ->
                        executionPromise.completeExceptionally(
                            mapChildWorkflowException(
                                failure, dataConverterWithChildWorkflowContext)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow started callback", () -> executionPromise.complete(execution));
              }
            },
            (result, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow failure callback",
                    () ->
                        resultPromise.completeExceptionally(
                            mapChildWorkflowException(
                                failure, dataConverterWithChildWorkflowContext)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow completion callback", () -> resultPromise.complete(result));
              }
            });
    AtomicBoolean callbackCalled = new AtomicBoolean();
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              if (!callbackCalled.getAndSet(true)) {
                cancellationCallback.apply(new CanceledFailure(reason));
              }
              return null;
            });

    Promise<R> result =
        resultPromise.thenApply(
            (b) ->
                dataConverterWithChildWorkflowContext.fromPayloads(
                    0, b, input.getResultClass(), input.getResultType()));
    return new ChildWorkflowOutput<>(result, executionPromise);
  }

  @Override
  public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
      ExecuteNexusOperationInput<R> input) {
    if (CancellationScope.current().isCancelRequested()) {
      CanceledFailure canceledFailure =
          new CanceledFailure("execute nexus operation called from a canceled scope");
      return new ExecuteNexusOperationOutput<>(
          Workflow.newFailedPromise(canceledFailure), Workflow.newFailedPromise(canceledFailure));
    }

    CompletablePromise<Optional<String>> operationPromise = Workflow.newPromise();
    CompletablePromise<Optional<Payload>> resultPromise = Workflow.newPromise();

    // Not using the context aware data converter because the context will not be available on the
    // worker side
    Optional<Payload> payload = dataConverter.toPayload(input.getArg());

    ScheduleNexusOperationCommandAttributes.Builder attributes =
        ScheduleNexusOperationCommandAttributes.newBuilder();
    payload.ifPresent(attributes::setInput);
    attributes.setOperation(input.getOperation());
    attributes.setService(input.getService());
    attributes.setEndpoint(input.getEndpoint());
    attributes.putAllNexusHeader(input.getHeaders());
    attributes.setScheduleToCloseTimeout(
        ProtobufTimeUtils.toProtoDuration(input.getOptions().getScheduleToCloseTimeout()));

    Functions.Proc1<Exception> cancellationCallback =
        replayContext.startNexusOperation(
            attributes.build(),
            (operationExec, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "nexus operation start failed callback",
                    () ->
                        operationPromise.completeExceptionally(
                            dataConverter.failureToException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "nexus operation started callback",
                    () -> operationPromise.complete(operationExec));
              }
            },
            (Optional<Payload> result, Failure failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "nexus operation failure callback",
                    () ->
                        resultPromise.completeExceptionally(
                            dataConverter.failureToException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "nexus operation completion callback", () -> resultPromise.complete(result));
              }
            });
    AtomicBoolean callbackCalled = new AtomicBoolean();
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              if (!callbackCalled.getAndSet(true)) {
                cancellationCallback.apply(new CanceledFailure(reason));
              }
              return null;
            });
    Promise<R> result =
        resultPromise.thenApply(
            (b) ->
                input.getResultClass() != Void.class
                    ? dataConverter.fromPayload(
                        b.get(), input.getResultClass(), input.getResultType())
                    : null);
    return new ExecuteNexusOperationOutput<>(result, operationPromise);
  }

  @SuppressWarnings("deprecation")
  private StartChildWorkflowExecutionParameters createChildWorkflowParameters(
      String workflowId,
      String name,
      ChildWorkflowOptions options,
      Header header,
      Optional<Payloads> input,
      @Nullable Memo memo) {
    final StartChildWorkflowExecutionCommandAttributes.Builder attributes =
        StartChildWorkflowExecutionCommandAttributes.newBuilder()
            .setWorkflowType(WorkflowType.newBuilder().setName(name).build());
    attributes.setWorkflowId(workflowId);
    attributes.setNamespace(OptionsUtils.safeGet(options.getNamespace()));
    input.ifPresent(attributes::setInput);
    attributes.setWorkflowRunTimeout(
        ProtobufTimeUtils.toProtoDuration(options.getWorkflowRunTimeout()));
    attributes.setWorkflowExecutionTimeout(
        ProtobufTimeUtils.toProtoDuration(options.getWorkflowExecutionTimeout()));
    attributes.setWorkflowTaskTimeout(
        ProtobufTimeUtils.toProtoDuration(options.getWorkflowTaskTimeout()));
    String taskQueue = options.getTaskQueue();
    if (taskQueue != null) {
      attributes.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));
    }
    if (options.getWorkflowIdReusePolicy() != null) {
      attributes.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    }
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      attributes.setRetryPolicy(toRetryPolicy(retryOptions));
    }
    attributes.setCronSchedule(OptionsUtils.safeGet(options.getCronSchedule()));

    if (memo != null) {
      attributes.setMemo(memo);
    }

    Map<String, Object> searchAttributes = options.getSearchAttributes();
    if (searchAttributes != null && !searchAttributes.isEmpty()) {
      if (options.getTypedSearchAttributes() != null) {
        throw new IllegalArgumentException(
            "Cannot have both typed search attributes and search attributes");
      }
      attributes.setSearchAttributes(SearchAttributesUtil.encode(searchAttributes));
    } else if (options.getTypedSearchAttributes() != null) {
      attributes.setSearchAttributes(
          SearchAttributesUtil.encodeTyped(options.getTypedSearchAttributes()));
    }

    List<ContextPropagator> propagators = options.getContextPropagators();
    if (propagators == null) {
      propagators = this.contextPropagators;
    }
    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(propagators));
    attributes.setHeader(grpcHeader);

    ParentClosePolicy parentClosePolicy = options.getParentClosePolicy();
    if (parentClosePolicy != null) {
      attributes.setParentClosePolicy(parentClosePolicy);
    }

    if (options.getVersioningIntent() != null) {
      attributes.setInheritBuildId(
          options
              .getVersioningIntent()
              .determineUseCompatibleFlag(
                  replayContext.getTaskQueue().equals(options.getTaskQueue())));
    }
    return new StartChildWorkflowExecutionParameters(attributes, options.getCancellationType());
  }

  private static Header extractContextsAndConvertToBytes(
      List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new Header(result);
  }

  private static RuntimeException mapChildWorkflowException(
      Exception failure, DataConverter dataConverterWithChildWorkflowContext) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof TemporalFailure) {
      ((TemporalFailure) failure).setDataConverter(dataConverterWithChildWorkflowContext);
    }
    if (failure instanceof CanceledFailure) {
      return (CanceledFailure) failure;
    }
    if (failure instanceof WorkflowException) {
      return (RuntimeException) failure;
    }
    if (failure instanceof ChildWorkflowFailure) {
      return (ChildWorkflowFailure) failure;
    }
    if (!(failure instanceof ChildWorkflowTaskFailedException)) {
      return new IllegalArgumentException("Unexpected exception type: ", failure);
    }
    ChildWorkflowTaskFailedException taskFailed = (ChildWorkflowTaskFailedException) failure;
    Throwable cause =
        dataConverterWithChildWorkflowContext.failureToException(
            taskFailed.getOriginalCauseFailure());
    ChildWorkflowFailure exception = taskFailed.getException();
    return new ChildWorkflowFailure(
        exception.getInitiatedEventId(),
        exception.getStartedEventId(),
        exception.getWorkflowType(),
        exception.getExecution(),
        exception.getNamespace(),
        exception.getRetryState(),
        cause);
  }

  @Override
  public Promise<Void> newTimer(Duration delay) {
    CompletablePromise<Void> p = Workflow.newPromise();
    Functions.Proc1<RuntimeException> cancellationHandler =
        replayContext.newTimer(
            delay,
            (e) ->
                runner.executeInWorkflowThread(
                    "timer-callback",
                    () -> {
                      if (e == null) {
                        p.complete(null);
                      } else {
                        p.completeExceptionally(e);
                      }
                    }));
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (r) -> {
              cancellationHandler.apply(new CanceledFailure(r));
              return r;
            });
    return p;
  }

  @Override
  public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    try {
      CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();
      replayContext.sideEffect(
          () -> {
            try {
              readOnly = true;
              R r = func.apply();
              return dataConverterWithCurrentWorkflowContext.toPayloads(r);
            } finally {
              readOnly = false;
            }
          },
          (p) ->
              runner.executeInWorkflowThread(
                  "side-effect-callback", () -> result.complete(Objects.requireNonNull(p))));
      return dataConverterWithCurrentWorkflowContext.fromPayloads(
          0, result.get(), resultClass, resultType);
    } catch (Exception e) {
      // SideEffect cannot throw normal exception as it can lead to non-deterministic behavior. So
      // fail the workflow task by throwing an Error.
      throw new Error(e);
    }
  }

  @Override
  public <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    try {
      return mutableSideEffectImpl(id, resultClass, resultType, updated, func);
    } catch (Exception e) {
      // MutableSideEffect cannot throw normal exception as it can lead to non-deterministic
      // behavior. So fail the workflow task by throwing an Error.
      throw new Error(e);
    }
  }

  private <R> R mutableSideEffectImpl(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();
    AtomicReference<R> unserializedResult = new AtomicReference<>();
    replayContext.mutableSideEffect(
        id,
        (storedBinary) -> {
          Optional<R> stored =
              storedBinary.map(
                  (b) ->
                      dataConverterWithCurrentWorkflowContext.fromPayloads(
                          0, Optional.of(b), resultClass, resultType));
          try {
            readOnly = true;
            R funcResult =
                Objects.requireNonNull(
                    func.apply(), "mutableSideEffect function " + "returned null");
            if (!stored.isPresent() || updated.test(stored.get(), funcResult)) {
              unserializedResult.set(funcResult);
              return dataConverterWithCurrentWorkflowContext.toPayloads(funcResult);
            }
            return Optional.empty(); // returned only when value doesn't need to be updated
          } finally {
            readOnly = false;
          }
        },
        (p) ->
            runner.executeInWorkflowThread(
                "mutable-side-effect-callback", () -> result.complete(Objects.requireNonNull(p))));

    if (!result.get().isPresent()) {
      throw new IllegalArgumentException("No value found for mutableSideEffectId=" + id);
    }
    // An optimization that avoids unnecessary deserialization of the result.
    R unserialized = unserializedResult.get();
    if (unserialized != null) {
      return unserialized;
    }
    return dataConverterWithCurrentWorkflowContext.fromPayloads(
        0, result.get(), resultClass, resultType);
  }

  @Override
  public int getVersion(String changeId, int minSupported, int maxSupported) {
    CompletablePromise<Integer> result = Workflow.newPromise();
    boolean markerExists =
        replayContext.getVersion(
            changeId,
            minSupported,
            maxSupported,
            (v, e) ->
                runner.executeInWorkflowThread(
                    "version-callback",
                    () -> {
                      if (v != null) {
                        result.complete(v);
                      } else {
                        result.completeExceptionally(e);
                      }
                    }));
    /*
     * If we are replaying a workflow and encounter a getVersion call it is possible that this call did not exist
     * on the original execution. If the call did not exist on the original execution then we cannot block on results
     * because it can lead to non-deterministic scheduling.
     * */
    if (replayContext.isReplaying()
        && !markerExists
        && replayContext.tryUseSdkFlag(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION)
        && minSupported == DEFAULT_VERSION) {
      return DEFAULT_VERSION;
    }

    try {
      return result.get();
    } catch (UnsupportedVersion.UnsupportedVersionException ex) {
      throw new UnsupportedVersion(ex);
    }
  }

  @Override
  public void registerQuery(RegisterQueryInput request) {
    queryDispatcher.registerQueryHandlers(request);
  }

  @Override
  public void registerSignalHandlers(RegisterSignalHandlersInput input) {
    signalDispatcher.registerSignalHandlers(input);
  }

  @Override
  public void registerUpdateHandlers(RegisterUpdateHandlersInput input) {
    updateDispatcher.registerUpdateHandlers(input);
  }

  @Override
  public void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput input) {
    signalDispatcher.registerDynamicSignalHandler(input);
  }

  @Override
  public void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input) {
    queryDispatcher.registerDynamicQueryHandler(input);
  }

  @Override
  public void registerDynamicUpdateHandler(RegisterDynamicUpdateHandlerInput input) {
    updateDispatcher.registerDynamicUpdateHandler(input);
  }

  @Override
  public UUID randomUUID() {
    return replayContext.randomUUID();
  }

  @Override
  public Random newRandom() {
    return replayContext.newRandom();
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public DataConverter getDataConverterWithCurrentWorkflowContext() {
    return dataConverterWithCurrentWorkflowContext;
  }

  boolean isReplaying() {
    return replayContext.isReplaying();
  }

  boolean isReadOnly() {
    return readOnly;
  }

  void setReadOnly(boolean readOnly) {
    this.readOnly = readOnly;
  }

  @Override
  public Map<Long, SignalHandlerInfo> getRunningSignalHandlers() {
    return signalDispatcher.getRunningSignalHandlers();
  }

  @Override
  public Map<String, UpdateHandlerInfo> getRunningUpdateHandlers() {
    return updateDispatcher.getRunningUpdateHandlers();
  }

  @Override
  public ReplayWorkflowContext getReplayContext() {
    return replayContext;
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    WorkflowExecution childExecution = input.getExecution();
    DataConverter dataConverterWithChildWorkflowContext =
        dataConverter.withContext(
            new WorkflowSerializationContext(
                replayContext.getNamespace(), childExecution.getWorkflowId()));
    SignalExternalWorkflowExecutionCommandAttributes.Builder attributes =
        SignalExternalWorkflowExecutionCommandAttributes.newBuilder();
    attributes.setSignalName(input.getSignalName());
    attributes.setExecution(childExecution);
    attributes.setHeader(HeaderUtils.toHeaderGrpc(input.getHeader(), null));
    Optional<Payloads> payloads = dataConverterWithChildWorkflowContext.toPayloads(input.getArgs());
    payloads.ifPresent(attributes::setInput);
    CompletablePromise<Void> result = Workflow.newPromise();
    Functions.Proc1<Exception> cancellationCallback =
        replayContext.signalExternalWorkflowExecution(
            attributes,
            (output, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow failure callback",
                    () ->
                        result.completeExceptionally(
                            dataConverterWithChildWorkflowContext.failureToException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow completion callback", () -> result.complete(output));
              }
            });
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.apply(new CanceledFailure(reason));
              return null;
            });
    return new SignalExternalOutput(result);
  }

  @Override
  public void sleep(Duration duration) {
    newTimer(duration).get();
  }

  @Override
  public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
    Promise<Void> timer = newTimer(timeout);
    WorkflowThread.await(reason, () -> (timer.isCompleted() || unblockCondition.get()));
    return !timer.isCompleted();
  }

  @Override
  public void await(String reason, Supplier<Boolean> unblockCondition) {
    WorkflowThread.await(reason, unblockCondition);
  }

  @SuppressWarnings("deprecation")
  @Override
  public void continueAsNew(ContinueAsNewInput input) {
    ContinueAsNewWorkflowExecutionCommandAttributes.Builder attributes =
        ContinueAsNewWorkflowExecutionCommandAttributes.newBuilder();
    String workflowType = input.getWorkflowType();
    if (workflowType != null) {
      attributes.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    }
    @Nullable ContinueAsNewOptions options = input.getOptions();
    if (options != null) {
      if (options.getWorkflowRunTimeout() != null) {
        attributes.setWorkflowRunTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getWorkflowRunTimeout()));
      }
      if (options.getWorkflowTaskTimeout() != null) {
        attributes.setWorkflowTaskTimeout(
            ProtobufTimeUtils.toProtoDuration(options.getWorkflowTaskTimeout()));
      }
      if (options.getTaskQueue() != null && !options.getTaskQueue().isEmpty()) {
        attributes.setTaskQueue(TaskQueue.newBuilder().setName(options.getTaskQueue()));
      }
      if (options.getRetryOptions() != null) {
        attributes.setRetryPolicy(toRetryPolicy(options.getRetryOptions()));
      } else if (replayContext.getRetryOptions() != null) {
        attributes.setRetryPolicy(toRetryPolicy(replayContext.getRetryOptions()));
      }
      Map<String, Object> searchAttributes = options.getSearchAttributes();
      if (searchAttributes != null && !searchAttributes.isEmpty()) {
        if (options.getTypedSearchAttributes() != null) {
          throw new IllegalArgumentException(
              "Cannot have typed search attributes and search attributes");
        }
        attributes.setSearchAttributes(SearchAttributesUtil.encode(searchAttributes));
      } else if (options.getTypedSearchAttributes() != null) {
        attributes.setSearchAttributes(
            SearchAttributesUtil.encodeTyped(options.getTypedSearchAttributes()));
      }
      Map<String, Object> memo = options.getMemo();
      if (memo != null) {
        attributes.setMemo(
            Memo.newBuilder()
                .putAllFields(intoPayloadMap(dataConverterWithCurrentWorkflowContext, memo)));
      }
      if (options.getVersioningIntent() != null) {
        attributes.setInheritBuildId(
            options
                .getVersioningIntent()
                .determineUseCompatibleFlag(
                    replayContext.getTaskQueue().equals(options.getTaskQueue())));
      }
    } else if (replayContext.getRetryOptions() != null) {
      // Have to copy retry options as server doesn't copy them.
      attributes.setRetryPolicy(toRetryPolicy(replayContext.getRetryOptions()));
    }

    List<ContextPropagator> propagators =
        options != null && options.getContextPropagators() != null
            ? options.getContextPropagators()
            : this.contextPropagators;
    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(input.getHeader(), extractContextsAndConvertToBytes(propagators));
    attributes.setHeader(grpcHeader);

    Optional<Payloads> payloads =
        dataConverterWithCurrentWorkflowContext.toPayloads(input.getArgs());
    payloads.ifPresent(attributes::setInput);

    replayContext.continueAsNewOnCompletion(attributes.build());
    WorkflowThread.exit();
  }

  @Override
  public CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input) {
    CompletablePromise<Void> result = Workflow.newPromise();
    replayContext.requestCancelExternalWorkflowExecution(
        input.getExecution(),
        (r, exception) -> {
          if (exception == null) {
            result.complete(null);
          } else {
            result.completeExceptionally(exception);
          }
        });
    return new CancelWorkflowOutput(result);
  }

  public Scope getMetricsScope() {
    return replayContext.getMetricsScope();
  }

  public boolean isLoggingEnabledInReplay() {
    return replayContext.getEnableLoggingInReplay();
  }

  @Override
  public void upsertSearchAttributes(Map<String, ?> searchAttributes) {
    Preconditions.checkArgument(searchAttributes != null, "null search attributes");
    Preconditions.checkArgument(!searchAttributes.isEmpty(), "empty search attributes");
    SearchAttributes attr = SearchAttributesUtil.encode(searchAttributes);
    replayContext.upsertSearchAttributes(attr);
  }

  @Override
  public void upsertTypedSearchAttributes(SearchAttributeUpdate<?>... searchAttributeUpdates) {
    SearchAttributes attr = SearchAttributesUtil.encodeTypedUpdates(searchAttributeUpdates);
    replayContext.upsertSearchAttributes(attr);
  }

  @Override
  public void upsertMemo(Map<String, Object> memo) {
    Preconditions.checkArgument(memo != null, "null memo");
    Preconditions.checkArgument(!memo.isEmpty(), "empty memo");
    replayContext.upsertMemo(
        Memo.newBuilder()
            .putAllFields(intoPayloadMap(dataConverterWithCurrentWorkflowContext, memo))
            .build());
  }

  @Nonnull
  public Object newWorkflowMethodThreadIntercepted(Runnable runnable, @Nullable String name) {
    return runner.newWorkflowThread(runnable, false, name);
  }

  @Nonnull
  public Object newWorkflowCallbackThreadIntercepted(Runnable runnable, @Nullable String name) {
    return runner.newCallbackThread(runnable, name);
  }

  @Override
  public Object newChildThread(Runnable runnable, boolean detached, String name) {
    return runner.newWorkflowThread(runnable, detached, name);
  }

  @Override
  public long currentTimeMillis() {
    return replayContext.currentTimeMillis();
  }

  /**
   * This WorkflowInboundCallsInterceptor is used during creation of the initial root workflow
   * thread and should be replaced with another specific implementation during initialization stage
   * {@code workflow.initialize()} performed inside the workflow root thread.
   *
   * @see SyncWorkflow#start(HistoryEvent, ReplayWorkflowContext)
   */
  private static final class InitialWorkflowInboundCallsInterceptor
      extends BaseRootWorkflowInboundCallsInterceptor {

    public InitialWorkflowInboundCallsInterceptor(SyncWorkflowContext workflowContext) {
      super(workflowContext);
    }

    @Override
    public WorkflowOutput execute(WorkflowInput input) {
      throw new UnsupportedOperationException(
          "SyncWorkflowContext should be initialized with a non-initial WorkflowInboundCallsInterceptor "
              + "before #execute can be called");
    }
  }

  @Nonnull
  @Override
  public WorkflowImplementationOptions getWorkflowImplementationOptions() {
    return workflowImplementationOptions;
  }

  @Override
  public Failure mapWorkflowExceptionToFailure(Throwable failure) {
    return dataConverterWithCurrentWorkflowContext.exceptionToFailure(failure);
  }

  @Nullable
  @Override
  public <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    return dataConverterWithCurrentWorkflowContext.fromPayloads(
        0, Optional.ofNullable(replayContext.getLastCompletionResult()), resultClass, resultType);
  }

  @Override
  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  @Override
  public Map<String, Object> getPropagatedContexts() {
    if (contextPropagators == null || contextPropagators.isEmpty()) {
      return new HashMap<>();
    }

    Map<String, Payload> headerData = new HashMap<>(replayContext.getHeader());
    Map<String, Object> contextData = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      contextData.put(propagator.getName(), propagator.deserializeContext(headerData));
    }

    return contextData;
  }

  public void setCurrentUpdateInfo(UpdateInfo updateInfo) {
    currentUpdateInfo.set(updateInfo);
  }

  public Optional<UpdateInfo> getCurrentUpdateInfo() {
    return Optional.ofNullable(currentUpdateInfo.get());
  }

  /** Simple wrapper over a failure just to allow completing the CompletablePromise as a failure */
  private static class FailureWrapperException extends RuntimeException {
    private final Failure failure;

    public FailureWrapperException(Failure failure) {
      this.failure = failure;
    }

    public Failure getFailure() {
      return failure;
    }
  }
}
