/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

import static io.temporal.internal.common.HeaderUtils.convertMapFromObjectToBytes;
import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.v1.ActivityType;
import io.temporal.common.v1.Header;
import io.temporal.common.v1.Memo;
import io.temporal.common.v1.Payload;
import io.temporal.common.v1.Payloads;
import io.temporal.common.v1.RetryPolicy;
import io.temporal.common.v1.SearchAttributes;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.common.v1.WorkflowType;
import io.temporal.decision.v1.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.decision.v1.ScheduleActivityTaskDecisionAttributes;
import io.temporal.enums.v1.RetryStatus;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.RetryParameters;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.ActivityTaskFailedException;
import io.temporal.internal.replay.ActivityTaskTimeoutException;
import io.temporal.internal.replay.ChildWorkflowTaskFailedException;
import io.temporal.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import io.temporal.internal.replay.DecisionContext;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.internal.replay.StartChildWorkflowExecutionParameters;
import io.temporal.taskqueue.v1.TaskQueue;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalExternalWorkflowException;
import io.temporal.workflow.Workflow;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SyncDecisionContext implements WorkflowOutboundCallsInterceptor {

  private static class SignalData {
    private final Optional<Payloads> payload;
    private final long eventId;

    private SignalData(Optional<Payloads> payload, long eventId) {
      this.payload = payload;
      this.eventId = eventId;
    }

    public Optional<Payloads> getPayload() {
      return payload;
    }

    public long getEventId() {
      return eventId;
    }
  }

  private static final Logger log = LoggerFactory.getLogger(SyncDecisionContext.class);

  private final DecisionContext context;
  private DeterministicRunner runner;
  private final DataConverter converter;
  private final List<ContextPropagator> contextPropagators;
  private WorkflowOutboundCallsInterceptor headInterceptor;
  private final WorkflowTimers timers = new WorkflowTimers();
  private final Map<String, Functions.Func1<Optional<Payloads>, Optional<Payloads>>>
      queryCallbacks = new HashMap<>();
  private final Map<String, Functions.Proc2<Optional<Payloads>, Long>> signalCallbacks =
      new HashMap<>();
  /**
   * Buffers signals which don't have registered listener. Key is signal type. Value is signal data.
   */
  private final Map<String, List<SignalData>> signalBuffers = new HashMap<>();

  private final Optional<Payloads> lastCompletionResult;

  public SyncDecisionContext(
      DecisionContext context,
      DataConverter converter,
      List<ContextPropagator> contextPropagators,
      Optional<Payloads> lastCompletionResult) {
    this.context = context;
    this.converter = converter;
    this.contextPropagators = contextPropagators;
    this.lastCompletionResult = lastCompletionResult;
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

  public WorkflowOutboundCallsInterceptor getWorkflowInterceptor() {
    // This is needed for unit tests that create DeterministicRunner directly.
    return headInterceptor == null ? this : headInterceptor;
  }

  public void setHeadInterceptor(WorkflowOutboundCallsInterceptor head) {
    if (headInterceptor == null) {
      runner.setInterceptorHead(head);
      this.headInterceptor = head;
    }
  }

  @Override
  public <T> Promise<T> executeActivity(
      String activityName,
      Class<T> returnClass,
      Type resultType,
      Object[] args,
      ActivityOptions options) {
    Optional<Payloads> input = converter.toPayloads(args);
    Promise<Optional<Payloads>> binaryResult = executeActivityOnce(activityName, options, input);
    if (returnClass == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply((r) -> converter.fromPayloads(r, returnClass, resultType));
  }

  private Promise<Optional<Payloads>> executeActivityOnce(
      String name, ActivityOptions options, Optional<Payloads> input) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteActivityParameters params = constructExecuteActivityParameters(name, options, input);
    Consumer<Exception> cancellationCallback =
        context.scheduleActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CanceledFailure(reason));
              return null;
            });
    return callback.result;
  }

  private class ActivityCallback {
    private CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();

    public void invoke(Optional<Payloads> output, Exception failure) {
      if (failure != null) {
        runner.executeInWorkflowThread(
            "activity failure callback",
            () -> result.completeExceptionally(mapActivityException(failure)));
      } else {
        runner.executeInWorkflowThread(
            "activity completion callback", () -> result.complete(output));
      }
    }
  }

  private RuntimeException mapActivityException(Exception failure) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof TemporalFailure) {
      ((TemporalFailure) failure).setDataConverter(getDataConverter());
    }
    if (failure instanceof CanceledFailure) {
      return (CanceledFailure) failure;
    }
    if (failure instanceof ActivityTaskFailedException) {
      ActivityTaskFailedException taskFailed = (ActivityTaskFailedException) failure;
      Throwable exception =
          FailureConverter.failureToException(taskFailed.getFailure(), getDataConverter());
      return new ActivityFailure(
          taskFailed.getScheduledEventId(),
          taskFailed.getStartedEventId(),
          taskFailed.getActivityType().getName(),
          taskFailed.getActivityId(),
          RetryStatus.RETRY_STATUS_TIMEOUT,
          "",
          exception);
    }
    if (failure instanceof ActivityTaskTimeoutException) {
      ActivityTaskTimeoutException timedOut = (ActivityTaskTimeoutException) failure;
      return new ActivityFailure(
          timedOut.getScheduledEventId(),
          timedOut.getStartedEventId(),
          timedOut.getActivityType().getName(),
          timedOut.getActivityId(),
          timedOut.getRetryStatus(),
          "",
          FailureConverter.failureToException(timedOut.getFailure(), converter));
    }
    if (failure instanceof ActivityFailure) {
      return (ActivityFailure) failure;
    }
    throw new IllegalArgumentException(
        "Unexpected exception type: " + failure.getClass().getName(), failure);
  }

  @Override
  public <R> Promise<R> executeLocalActivity(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      Object[] args,
      LocalActivityOptions options) {
    long startTime = WorkflowInternal.currentTimeMillis();
    return WorkflowRetryerInternal.retryAsync(
        (attempt, currentStart) ->
            executeLocalActivityOnce(
                activityName,
                options,
                args,
                resultClass,
                resultType,
                currentStart - startTime,
                attempt),
        1,
        startTime);
  }

  private <T> Promise<T> executeLocalActivityOnce(
      String name,
      LocalActivityOptions options,
      Object[] args,
      Class<T> returnClass,
      Type returnType,
      long elapsed,
      int attempt) {
    Optional<Payloads> input = converter.toPayloads(args);
    Promise<Optional<Payloads>> binaryResult =
        executeLocalActivityOnce(name, options, input, elapsed, attempt);
    if (returnClass == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply((r) -> converter.fromPayloads(r, returnClass, returnType));
  }

  private Promise<Optional<Payloads>> executeLocalActivityOnce(
      String name,
      LocalActivityOptions options,
      Optional<Payloads> input,
      long elapsed,
      int attempt) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteLocalActivityParameters params =
        constructExecuteLocalActivityParameters(name, options, input, elapsed, attempt);
    Consumer<Exception> cancellationCallback =
        context.scheduleLocalActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CanceledFailure(reason));
              return null;
            });
    return callback.result;
  }

  private ExecuteActivityParameters constructExecuteActivityParameters(
      String name, ActivityOptions options, Optional<Payloads> input) {
    String taskQueue = options.getTaskQueue();
    if (taskQueue == null) {
      taskQueue = context.getTaskQueue();
    }
    ScheduleActivityTaskDecisionAttributes.Builder attributes =
        ScheduleActivityTaskDecisionAttributes.newBuilder()
            .setActivityType(ActivityType.newBuilder().setName(name))
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue))
            .setScheduleToStartTimeoutSeconds(roundUpToSeconds(options.getScheduleToStartTimeout()))
            .setStartToCloseTimeoutSeconds(roundUpToSeconds(options.getStartToCloseTimeout()))
            .setScheduleToCloseTimeoutSeconds(roundUpToSeconds(options.getScheduleToCloseTimeout()))
            .setHeartbeatTimeoutSeconds(roundUpToSeconds(options.getHeartbeatTimeout()));

    if (input.isPresent()) {
      attributes.setInput(input.get());
    }

    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      attributes.setRetryPolicy(toRetryPolicy(retryOptions));
    }

    // Set the context value.  Use the context propagators from the ActivityOptions
    // if present, otherwise use the ones configured on the DecisionContext
    List<ContextPropagator> propagators = options.getContextPropagators();
    if (propagators == null) {
      propagators = this.contextPropagators;
    }
    Header header = toHeaderGrpc(extractContextsAndConvertToBytes(propagators));
    if (header != null) {
      attributes.setHeader(header);
    }
    return new ExecuteActivityParameters(attributes, options.getCancellationType());
  }

  private static RetryPolicy.Builder toRetryPolicy(RetryOptions retryOptions) {
    return RetryPolicy.newBuilder()
        .setInitialIntervalInSeconds(roundUpToSeconds(retryOptions.getInitialInterval()))
        .setMaximumIntervalInSeconds(roundUpToSeconds(retryOptions.getMaximumInterval()))
        .setBackoffCoefficient(retryOptions.getBackoffCoefficient())
        .setMaximumAttempts(retryOptions.getMaximumAttempts())
        .addAllNonRetryableErrorTypes(Arrays.asList(retryOptions.getDoNotRetry()));
  }

  private ExecuteLocalActivityParameters constructExecuteLocalActivityParameters(
      String name,
      LocalActivityOptions options,
      Optional<Payloads> input,
      long elapsed,
      int attempt) {
    options = LocalActivityOptions.newBuilder(options).validateAndBuildWithDefaults();

    PollForActivityTaskResponse.Builder activityTask =
        PollForActivityTaskResponse.newBuilder()
            .setWorkflowNamespace(this.context.getNamespace())
            .setWorkflowExecution(this.context.getWorkflowExecution())
            .setScheduledTimestamp(System.currentTimeMillis())
            .setStartToCloseTimeoutSeconds(roundUpToSeconds(options.getStartToCloseTimeout()))
            .setScheduleToCloseTimeoutSeconds(roundUpToSeconds(options.getScheduleToCloseTimeout()))
            .setStartedTimestamp(System.currentTimeMillis())
            .setActivityType(ActivityType.newBuilder().setName(name))
            .setAttempt(attempt);
    if (input.isPresent()) {
      activityTask.setInput(input.get());
    }
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      activityTask.setRetryPolicy(toRetryPolicy(retryOptions));
    }
    return new ExecuteLocalActivityParameters(activityTask, elapsed);
  }

  @Override
  public <R> WorkflowResult<R> executeChildWorkflow(
      String workflowType,
      Class<R> returnClass,
      Type returnType,
      Object[] args,
      ChildWorkflowOptions options) {
    Optional<Payloads> input = converter.toPayloads(args);
    CompletablePromise<WorkflowExecution> execution = Workflow.newPromise();
    Promise<Optional<Payloads>> output =
        executeChildWorkflow(workflowType, options, input, execution);
    Promise<R> result = output.thenApply((b) -> converter.fromPayloads(b, returnClass, returnType));
    return new WorkflowResult<>(result, execution);
  }

  private Promise<Optional<Payloads>> executeChildWorkflow(
      String name,
      ChildWorkflowOptions options,
      Optional<Payloads> input,
      CompletablePromise<WorkflowExecution> executionResult) {
    CompletablePromise<Optional<Payloads>> result = Workflow.newPromise();
    if (CancellationScope.current().isCancelRequested()) {
      CanceledFailure CanceledFailure =
          new CanceledFailure("execute called from a cancelled scope");
      executionResult.completeExceptionally(CanceledFailure);
      result.completeExceptionally(CanceledFailure);
      return result;
    }
    RetryParameters retryParameters = null;
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      retryParameters = new RetryParameters(retryOptions);
    }
    List<ContextPropagator> propagators = options.getContextPropagators();
    if (propagators == null) {
      propagators = this.contextPropagators;
    }

    StartChildWorkflowExecutionParameters parameters =
        new StartChildWorkflowExecutionParameters.Builder()
            .setWorkflowType(WorkflowType.newBuilder().setName(name).build())
            .setWorkflowId(options.getWorkflowId())
            .setInput(input.orElse(null))
            .setWorkflowRunTimeoutSeconds(roundUpToSeconds(options.getWorkflowRunTimeout()))
            .setWorkflowExecutionTimeoutSeconds(
                roundUpToSeconds(options.getWorkflowExecutionTimeout()))
            .setNamespace(options.getNamespace())
            .setTaskQueue(options.getTaskQueue())
            .setWorkflowTaskTimeoutSeconds(roundUpToSeconds(options.getWorkflowTaskTimeout()))
            .setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy())
            .setRetryParameters(retryParameters)
            .setCronSchedule(options.getCronSchedule())
            .setContext(extractContextsAndConvertToBytes(propagators))
            .setParentClosePolicy(options.getParentClosePolicy())
            .setCancellationType(options.getCancellationType())
            .build();
    Consumer<Exception> cancellationCallback =
        context.startChildWorkflow(
            parameters,
            (we) ->
                runner.executeInWorkflowThread(
                    "child workflow started callback", () -> executionResult.complete(we)),
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
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CanceledFailure(reason));
              return null;
            });
    return result;
  }

  private Map<String, Payload> extractContextsAndConvertToBytes(
      List<ContextPropagator> contextPropagators) {
    if (contextPropagators == null) {
      return null;
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return result;
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
        FailureConverter.failureToException(taskFailed.getFailure(), getDataConverter());
    // To support WorkflowExecutionAlreadyStarted set at handleStartChildWorkflowExecutionFailed
    if (cause == null) {
      cause = failure.getCause();
    }
    return new ChildWorkflowFailure(
        0,
        0,
        taskFailed.getWorkflowType().getName(),
        taskFailed.getWorkflowExecution(),
        null,
        taskFailed.getRetryStatus(),
        cause);
  }

  @Override
  public Promise<Void> newTimer(Duration delay) {
    Objects.requireNonNull(delay);
    long delaySeconds = roundUpToSeconds(delay);
    if (delaySeconds < 0) {
      throw new IllegalArgumentException("negative delay");
    }
    if (delaySeconds == 0) {
      return Workflow.newPromise(null);
    }
    CompletablePromise<Void> timer = Workflow.newPromise();
    long fireTime = context.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
    timers.addTimer(fireTime, timer);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              timers.removeTimer(fireTime, timer);
              timer.completeExceptionally(new CanceledFailure(reason));
              return null;
            });
    return timer;
  }

  @Override
  public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    DataConverter dataConverter = getDataConverter();
    Optional<Payloads> result =
        context.sideEffect(
            () -> {
              R r = func.apply();
              return dataConverter.toPayloads(r);
            });
    return dataConverter.fromPayloads(result, resultClass, resultType);
  }

  @Override
  public <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    AtomicReference<R> unserializedResult = new AtomicReference<>();
    Optional<Payloads> payloads =
        context.mutableSideEffect(
            id,
            converter,
            (storedBinary) -> {
              Optional<R> stored =
                  storedBinary.map(
                      (b) -> converter.fromPayloads(Optional.of(b), resultClass, resultType));
              R funcResult =
                  Objects.requireNonNull(
                      func.apply(), "mutableSideEffect function " + "returned null");
              if (!stored.isPresent() || updated.test(stored.get(), funcResult)) {
                unserializedResult.set(funcResult);
                return converter.toPayloads(funcResult);
              }
              return Optional.empty(); // returned only when value doesn't need to be updated
            });
    if (!payloads.isPresent()) {
      throw new IllegalArgumentException(
          "No value found for mutableSideEffectId="
              + id
              + ", during replay it usually indicates a different workflow runId than the original one");
    }
    // An optimization that avoids unnecessary deserialization of the result.
    R unserialized = unserializedResult.get();
    if (unserialized != null) {
      return unserialized;
    }
    return converter.fromPayloads(payloads, resultClass, resultType);
  }

  @Override
  public int getVersion(String changeId, int minSupported, int maxSupported) {
    return context.getVersion(changeId, converter, minSupported, maxSupported);
  }

  void fireTimers() {
    timers.fireTimers(context.currentTimeMillis());
  }

  boolean hasTimersToFire() {
    return timers.hasTimersToFire(context.currentTimeMillis());
  }

  long getNextFireTime() {
    return timers.getNextFireTime();
  }

  public Optional<Payloads> query(String type, Optional<Payloads> args) {
    Functions.Func1<Optional<Payloads>, Optional<Payloads>> callback = queryCallbacks.get(type);
    if (callback == null) {
      throw new IllegalArgumentException(
          "Unknown query type: " + type + ", knownTypes=" + queryCallbacks.keySet());
    }
    return callback.apply(args);
  }

  public void signal(String signalName, Optional<Payloads> args, long eventId) {
    Functions.Proc2<Optional<Payloads>, Long> callback = signalCallbacks.get(signalName);
    if (callback == null) {
      List<SignalData> buffer = signalBuffers.get(signalName);
      if (buffer == null) {
        buffer = new ArrayList<>();
        signalBuffers.put(signalName, buffer);
      }
      buffer.add(new SignalData(args, eventId));
    } else {
      callback.apply(args, eventId);
    }
  }

  @Override
  public void registerQuery(
      String queryType,
      Class<?>[] argTypes,
      Type[] genericArgTypes,
      Functions.Func1<Object[], Object> callback) {
    if (queryCallbacks.containsKey(queryType)) {
      throw new IllegalStateException("Query \"" + queryType + "\" is already registered");
    }
    queryCallbacks.put(
        queryType,
        (input) -> {
          Object[] args = converter.arrayFromPayloads(input, argTypes, genericArgTypes);
          Object result = callback.apply(args);
          return converter.toPayloads(result);
        });
  }

  @Override
  public void registerSignal(
      String signalType,
      Class<?>[] argTypes,
      Type[] genericArgTypes,
      Functions.Proc1<Object[]> callback) {
    if (signalCallbacks.containsKey(signalType)) {
      throw new IllegalStateException("Signal \"" + signalType + "\" is already registered");
    }
    Functions.Proc2<Optional<Payloads>, Long> signalCallback =
        (input, eventId) -> {
          try {
            Object[] args = converter.arrayFromPayloads(input, argTypes, genericArgTypes);
            callback.apply(args);
          } catch (DataConverterException e) {
            logSerializationException(signalType, eventId, e);
          }
        };
    List<SignalData> buffer = signalBuffers.remove(signalType);
    if (buffer != null) {
      for (SignalData signalData : buffer) {
        signalCallback.apply(signalData.getPayload(), signalData.getEventId());
      }
    }
    signalCallbacks.put(signalType, signalCallback);
  }

  void logSerializationException(
      String signalName, Long eventId, DataConverterException exception) {
    log.error(
        "Failure deserializing signal input for \""
            + signalName
            + "\" at eventId "
            + eventId
            + ". Dropping it.",
        exception);
    Workflow.getMetricsScope().counter(MetricsType.CORRUPTED_SIGNALS_COUNTER).inc(1);
  }

  @Override
  public UUID randomUUID() {
    return context.randomUUID();
  }

  @Override
  public Random newRandom() {
    return context.newRandom();
  }

  public DataConverter getDataConverter() {
    return converter;
  }

  boolean isReplaying() {
    return context.isReplaying();
  }

  public DecisionContext getContext() {
    return context;
  }

  @Override
  public Promise<Void> signalExternalWorkflow(
      WorkflowExecution execution, String signalName, Object[] args) {
    SignalExternalWorkflowParameters parameters = new SignalExternalWorkflowParameters();
    parameters.setSignalName(signalName);
    parameters.setWorkflowId(execution.getWorkflowId());
    parameters.setRunId(execution.getRunId());
    Optional<Payloads> input = getDataConverter().toPayloads(args);
    parameters.setInput(input);
    CompletablePromise<Void> result = Workflow.newPromise();

    Consumer<Exception> cancellationCallback =
        context.signalWorkflowExecution(
            parameters,
            (output, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "child workflow failure callback",
                    () -> result.completeExceptionally(mapSignalWorkflowException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "child workflow completion callback", () -> result.complete(output));
              }
            });
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CanceledFailure(reason));
              return null;
            });
    return result;
  }

  @Override
  public void sleep(Duration duration) {
    WorkflowThread.await(
        duration.toMillis(),
        "sleep",
        () -> {
          CancellationScope.throwCancelled();
          return false;
        });
  }

  @Override
  public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
    return WorkflowThread.await(timeout.toMillis(), reason, unblockCondition);
  }

  @Override
  public void await(String reason, Supplier<Boolean> unblockCondition) {
    WorkflowThread.await(reason, unblockCondition);
  }

  @Override
  public void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args) {
    ContinueAsNewWorkflowExecutionDecisionAttributes.Builder attributes =
        ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder();
    if (workflowType.isPresent()) {
      attributes.setWorkflowType(WorkflowType.newBuilder().setName(workflowType.get()));
    }
    if (options.isPresent()) {
      ContinueAsNewOptions ops = options.get();
      attributes.setWorkflowRunTimeoutSeconds(roundUpToSeconds(ops.getWorkflowRunTimeout()));
      attributes.setWorkflowTaskTimeoutSeconds(roundUpToSeconds(ops.getWorkflowTaskTimeout()));
      if (!ops.getTaskQueue().isEmpty()) {
        attributes.setTaskQueue(TaskQueue.newBuilder().setName(ops.getTaskQueue()));
      }
      Map<String, Object> memo = ops.getMemo();
      if (memo != null) {
        attributes.setMemo(
            Memo.newBuilder().putAllFields(convertMapFromObjectToBytes(memo, getDataConverter())));
      }
      Map<String, Object> searchAttributes = ops.getSearchAttributes();
      if (searchAttributes != null) {
        attributes.setSearchAttributes(
            SearchAttributes.newBuilder()
                .putAllIndexedFields(
                    convertMapFromObjectToBytes(searchAttributes, getDataConverter())));
      }
    }
    Optional<Payloads> payloads = getDataConverter().toPayloads(args);
    if (payloads.isPresent()) {
      attributes.setInput(payloads.get());
    }
    // TODO(maxim): Find out what to do about header
    ContinueAsNewWorkflowExecutionParameters parameters =
        new ContinueAsNewWorkflowExecutionParameters(attributes.build());
    context.continueAsNewOnCompletion(parameters);
    WorkflowThread.exit(null);
  }

  @Override
  public Promise<Void> cancelWorkflow(WorkflowExecution execution) {
    return context.requestCancelWorkflowExecution(execution);
  }

  private RuntimeException mapSignalWorkflowException(Exception failure) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof CanceledFailure) {
      return (CanceledFailure) failure;
    }

    if (!(failure instanceof SignalExternalWorkflowException)) {
      return new IllegalArgumentException("Unexpected exception type: ", failure);
    }
    return (SignalExternalWorkflowException) failure;
  }

  public Scope getMetricsScope() {
    return context.getMetricsScope();
  }

  public boolean isLoggingEnabledInReplay() {
    return context.getEnableLoggingInReplay();
  }

  public <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    DataConverter dataConverter = getDataConverter();
    return dataConverter.fromPayloads(lastCompletionResult, resultClass, resultType);
  }

  @Override
  public void upsertSearchAttributes(Map<String, Object> searchAttributes) {
    if (searchAttributes.isEmpty()) {
      throw new IllegalArgumentException("Empty search attributes");
    }

    SearchAttributes attr =
        InternalUtils.convertMapToSearchAttributes(searchAttributes, getDataConverter());
    context.upsertSearchAttributes(attr);
  }

  @Override
  public Object newThread(Runnable runnable, boolean detached, String name) {
    return runner.newThread(runnable, detached, name);
  }
}
