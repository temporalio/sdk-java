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
import static io.temporal.internal.common.SerializerUtils.toRetryPolicy;

import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
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
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.common.ActivityOptionUtils;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.replay.ChildWorkflowTaskFailedException;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.WorkflowContext;
import io.temporal.internal.statemachines.ExecuteActivityParameters;
import io.temporal.internal.statemachines.ExecuteLocalActivityParameters;
import io.temporal.internal.statemachines.StartChildWorkflowExecutionParameters;
import io.temporal.internal.statemachines.UnsupportedVersion;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

// TODO separate WorkflowOutboundCallsInterceptor functionality from this class into
// RootWorkflowOutboundInterceptor
/**
 * Root, the most top level WorkflowContext that unites all relevant contexts, handlers, options,
 * states, etc. It's created when SyncWorkflow which represent a context of a workflow type
 * definition on the worker meets ReplayWorkflowContext which contains information from the
 * WorkflowTask
 */
final class SyncWorkflowContext implements WorkflowContext, WorkflowOutboundCallsInterceptor {
  private final WorkflowImplementationOptions workflowImplementationOptions;
  private final DataConverter dataConverter;
  private final List<ContextPropagator> contextPropagators;
  private final SignalDispatcher signalDispatcher;
  private final QueryDispatcher queryDispatcher;

  // initialized later when these entities are created
  private ReplayWorkflowContext replayContext;
  private DeterministicRunner runner;

  private WorkflowInboundCallsInterceptor headInboundInterceptor;
  private WorkflowOutboundCallsInterceptor headOutboundInterceptor;

  private ActivityOptions defaultActivityOptions = null;
  private Map<String, ActivityOptions> activityOptionsMap;
  private LocalActivityOptions defaultLocalActivityOptions = null;
  private Map<String, LocalActivityOptions> localActivityOptionsMap;

  public SyncWorkflowContext(
      @Nullable WorkflowImplementationOptions workflowImplementationOptions,
      DataConverter dataConverter,
      List<ContextPropagator> contextPropagators) {
    this.dataConverter = dataConverter;
    this.contextPropagators = contextPropagators;
    this.signalDispatcher = new SignalDispatcher(dataConverter);
    this.queryDispatcher = new QueryDispatcher(dataConverter);
    if (workflowImplementationOptions != null) {
      this.defaultActivityOptions = workflowImplementationOptions.getDefaultActivityOptions();
      this.activityOptionsMap = new HashMap<>(workflowImplementationOptions.getActivityOptions());
      this.defaultLocalActivityOptions =
          workflowImplementationOptions.getDefaultLocalActivityOptions();
      this.localActivityOptionsMap =
          new HashMap<>(workflowImplementationOptions.getLocalActivityOptions());
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
    Optional<Payloads> args = dataConverter.toPayloads(input.getArgs());
    Promise<Optional<Payloads>> binaryResult =
        executeActivityOnce(input.getActivityName(), input.getOptions(), input.getHeader(), args);
    if (input.getResultType() == Void.TYPE) {
      return new ActivityOutput<>(binaryResult.thenApply((r) -> null));
    }
    return new ActivityOutput<>(
        binaryResult.thenApply(
            (r) ->
                dataConverter.fromPayloads(0, r, input.getResultClass(), input.getResultType())));
  }

  private Promise<Optional<Payloads>> executeActivityOnce(
      String name, ActivityOptions options, Header header, Optional<Payloads> input) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteActivityParameters params =
        constructExecuteActivityParameters(name, options, header, input);
    Functions.Proc1<Exception> cancellationCallback =
        replayContext.scheduleActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.apply(new CanceledFailure(reason));
              return null;
            });
    return callback.result;
  }

  public void handleInterceptedSignal(WorkflowInboundCallsInterceptor.SignalInput input) {
    signalDispatcher.handleInterceptedSignal(input);
  }

  public void handleSignal(String signalName, Optional<Payloads> input, long eventId) {
    signalDispatcher.handleSignal(signalName, input, eventId);
  }

  public WorkflowInboundCallsInterceptor.QueryOutput handleInterceptedQuery(
      WorkflowInboundCallsInterceptor.QueryInput input) {
    return queryDispatcher.handleInterceptedQuery(input);
  }

  public Optional<Payloads> handleQuery(String queryName, Optional<Payloads> input) {
    return queryDispatcher.handleQuery(queryName, input);
  }

  private class ActivityCallback {
    private final CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();

    public void invoke(Optional<Payloads> output, Failure failure) {
      if (failure != null) {
        runner.executeInWorkflowThread(
            "activity failure callback",
            () ->
                result.completeExceptionally(
                    FailureConverter.failureToException(failure, getDataConverter())));
      } else {
        runner.executeInWorkflowThread(
            "activity completion callback", () -> result.complete(output));
      }
    }
  }

  @Override
  public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
    long startTime = WorkflowInternal.currentTimeMillis();
    return new LocalActivityOutput<>(
        WorkflowRetryerInternal.retryAsync(
            (attempt, currentStart) -> executeLocalActivityOnce(input, attempt), 1, startTime));
  }

  private <T> Promise<T> executeLocalActivityOnce(LocalActivityInput<T> input, int attempt) {
    Optional<Payloads> payloads = dataConverter.toPayloads(input.getArgs());
    Promise<Optional<Payloads>> binaryResult =
        executeLocalActivityOnce(
            input.getActivityName(), input.getOptions(), input.getHeader(), payloads, attempt);
    if (input.getResultClass() == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply(
        (r) -> dataConverter.fromPayloads(0, r, input.getResultClass(), input.getResultType()));
  }

  private Promise<Optional<Payloads>> executeLocalActivityOnce(
      String name,
      LocalActivityOptions options,
      Header header,
      Optional<Payloads> input,
      int attempt) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteLocalActivityParameters params =
        constructExecuteLocalActivityParameters(name, options, header, input, attempt);
    Functions.Proc cancellationCallback =
        replayContext.scheduleLocalActivityTask(params, callback::invoke);
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
            .setRequestEagerExecution(!options.isEagerExecutionDisabled());

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
    return new ExecuteActivityParameters(attributes, options.getCancellationType());
  }

  private ExecuteLocalActivityParameters constructExecuteLocalActivityParameters(
      String name,
      LocalActivityOptions options,
      Header header,
      Optional<Payloads> input,
      int attempt) {
    options = LocalActivityOptions.newBuilder(options).validateAndBuildWithDefaults();

    PollActivityTaskQueueResponse.Builder activityTask =
        PollActivityTaskQueueResponse.newBuilder()
            .setActivityId(this.replayContext.randomUUID().toString())
            .setWorkflowNamespace(this.replayContext.getNamespace())
            .setWorkflowType(this.replayContext.getWorkflowType())
            .setWorkflowExecution(this.replayContext.getWorkflowExecution())
            .setScheduledTime(ProtobufTimeUtils.getCurrentProtoTime())
            .setStartToCloseTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()))
            .setScheduleToCloseTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()))
            .setStartedTime(ProtobufTimeUtils.getCurrentProtoTime())
            .setActivityType(ActivityType.newBuilder().setName(name))
            .setAttempt(attempt);
    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(contextPropagators));
    activityTask.setHeader(grpcHeader);
    input.ifPresent(activityTask::setInput);
    RetryOptions retryOptions = options.getRetryOptions();
    activityTask.setRetryPolicy(
        toRetryPolicy(RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults()));
    Duration localRetryThreshold = options.getLocalRetryThreshold();
    if (localRetryThreshold == null) {
      localRetryThreshold = replayContext.getWorkflowTaskTimeout().multipliedBy(6);
    }
    return new ExecuteLocalActivityParameters(
        activityTask, localRetryThreshold, options.isDoNotIncludeArgumentsIntoMarker());
  }

  @Override
  public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
    Optional<Payloads> payloads = dataConverter.toPayloads(input.getArgs());
    CompletablePromise<WorkflowExecution> execution = Workflow.newPromise();
    Promise<Optional<Payloads>> output =
        executeChildWorkflow(
            input.getWorkflowId(),
            input.getWorkflowType(),
            input.getOptions(),
            input.getHeader(),
            payloads,
            execution);
    Promise<R> result =
        output.thenApply(
            (b) -> dataConverter.fromPayloads(0, b, input.getResultClass(), input.getResultType()));
    return new ChildWorkflowOutput<>(result, execution);
  }

  private Promise<Optional<Payloads>> executeChildWorkflow(
      String workflowId,
      String name,
      ChildWorkflowOptions options,
      Header header,
      Optional<Payloads> input,
      CompletablePromise<WorkflowExecution> startResult) {
    CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();
    if (CancellationScope.current().isCancelRequested()) {
      CanceledFailure CanceledFailure = new CanceledFailure("execute called from a canceled scope");
      startResult.completeExceptionally(CanceledFailure);
      result.completeExceptionally(CanceledFailure);
      return result;
    }

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

    Map<String, Object> memo = options.getMemo();
    if (memo != null) {
      attributes.setMemo(Memo.newBuilder().putAllFields(intoPayloadMap(getDataConverter(), memo)));
    }

    Map<String, Object> searchAttributes = options.getSearchAttributes();
    if (searchAttributes != null && !searchAttributes.isEmpty()) {
      attributes.setSearchAttributes(SearchAttributesUtil.encode(searchAttributes));
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
    StartChildWorkflowExecutionParameters parameters =
        new StartChildWorkflowExecutionParameters(attributes, options.getCancellationType());

    Functions.Proc1<Exception> cancellationCallback =
        replayContext.startChildWorkflow(
            parameters,
            (execution, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow start failed callback",
                    () -> startResult.completeExceptionally(mapChildWorkflowException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow started callback", () -> startResult.complete(execution));
              }
            },
            (output, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow failure callback",
                    () -> result.completeExceptionally(mapChildWorkflowException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow completion callback", () -> result.complete(output));
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
    return result;
  }

  private Header extractContextsAndConvertToBytes(List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new Header(result);
  }

  private RuntimeException mapChildWorkflowException(Exception failure) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof TemporalFailure) {
      ((TemporalFailure) failure).setDataConverter(getDataConverter());
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
        FailureConverter.failureToException(
            taskFailed.getOriginalCauseFailure(), getDataConverter());
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
      DataConverter dataConverter = getDataConverter();
      CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();
      replayContext.sideEffect(
          () -> {
            R r = func.apply();
            return dataConverter.toPayloads(r);
          },
          (p) ->
              runner.executeInWorkflowThread(
                  "side-effect-callback", () -> result.complete(Objects.requireNonNull(p))));
      return dataConverter.fromPayloads(0, result.get(), resultClass, resultType);
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
                  (b) -> dataConverter.fromPayloads(0, Optional.of(b), resultClass, resultType));
          R funcResult =
              Objects.requireNonNull(func.apply(), "mutableSideEffect function " + "returned null");
          if (!stored.isPresent() || updated.test(stored.get(), funcResult)) {
            unserializedResult.set(funcResult);
            return dataConverter.toPayloads(funcResult);
          }
          return Optional.empty(); // returned only when value doesn't need to be updated
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
    return dataConverter.fromPayloads(0, result.get(), resultClass, resultType);
  }

  @Override
  public int getVersion(String changeId, int minSupported, int maxSupported) {
    CompletablePromise<Integer> result = Workflow.newPromise();
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
  public void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput input) {
    signalDispatcher.registerDynamicSignalHandler(input);
  }

  @Override
  public void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input) {
    queryDispatcher.registerDynamicQueryHandler(input);
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

  boolean isReplaying() {
    return replayContext.isReplaying();
  }

  @Override
  public ReplayWorkflowContext getReplayContext() {
    return replayContext;
  }

  @Override
  public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
    SignalExternalWorkflowExecutionCommandAttributes.Builder attributes =
        SignalExternalWorkflowExecutionCommandAttributes.newBuilder();
    attributes.setSignalName(input.getSignalName());
    attributes.setExecution(input.getExecution());
    Optional<Payloads> payloads = getDataConverter().toPayloads(input.getArgs());
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
                            FailureConverter.failureToException(failure, getDataConverter())));
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

      if (!options.getTaskQueue().isEmpty()) {
        attributes.setTaskQueue(TaskQueue.newBuilder().setName(options.getTaskQueue()));
      }
      Map<String, Object> searchAttributes = options.getSearchAttributes();
      if (searchAttributes != null) {
        attributes.setSearchAttributes(SearchAttributesUtil.encode(searchAttributes));
      }
      Map<String, Object> memo = options.getMemo();
      if (memo != null) {
        attributes.setMemo(
            Memo.newBuilder().putAllFields(intoPayloadMap(getDataConverter(), memo)));
      }
    }

    List<ContextPropagator> propagators =
        options != null && options.getContextPropagators() != null
            ? options.getContextPropagators()
            : this.contextPropagators;
    io.temporal.api.common.v1.Header grpcHeader =
        toHeaderGrpc(input.getHeader(), extractContextsAndConvertToBytes(propagators));
    attributes.setHeader(grpcHeader);

    Optional<Payloads> payloads = getDataConverter().toPayloads(input.getArgs());
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
  public Failure mapExceptionToFailure(Throwable failure) {
    return FailureConverter.exceptionToFailure(failure, dataConverter);
  }

  @Nullable
  @Override
  public <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    return dataConverter.fromPayloads(
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
}
