/*
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

package com.uber.cadence.internal.sync;

import static com.uber.cadence.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.cadence.ActivityType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.activity.LocalActivityOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.RetryParameters;
import com.uber.cadence.internal.replay.ActivityTaskFailedException;
import com.uber.cadence.internal.replay.ActivityTaskTimeoutException;
import com.uber.cadence.internal.replay.ChildWorkflowTaskFailedException;
import com.uber.cadence.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.internal.replay.ExecuteActivityParameters;
import com.uber.cadence.internal.replay.ExecuteLocalActivityParameters;
import com.uber.cadence.internal.replay.SignalExternalWorkflowParameters;
import com.uber.cadence.internal.replay.StartChildWorkflowExecutionParameters;
import com.uber.cadence.workflow.ActivityException;
import com.uber.cadence.workflow.ActivityFailureException;
import com.uber.cadence.workflow.ActivityTimeoutException;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.ChildWorkflowException;
import com.uber.cadence.workflow.ChildWorkflowFailureException;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.ChildWorkflowTimedOutException;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.ContinueAsNewOptions;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInterceptor;
import com.uber.m3.tally.Scope;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SyncDecisionContext implements WorkflowInterceptor {

  private static final Logger log = LoggerFactory.getLogger(SyncDecisionContext.class);

  private final DecisionContext context;
  private DeterministicRunner runner;
  private final DataConverter converter;
  private final WorkflowInterceptor headInterceptor;
  private final WorkflowTimers timers = new WorkflowTimers();
  private final Map<String, Functions.Func1<byte[], byte[]>> queryCallbacks = new HashMap<>();
  private final byte[] lastCompletionResult;

  public SyncDecisionContext(
      DecisionContext context,
      DataConverter converter,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      byte[] lastCompletionResult) {
    this.context = context;
    this.converter = converter;
    WorkflowInterceptor interceptor = interceptorFactory.apply(this);
    if (interceptor == null) {
      log.warn("WorkflowInterceptor factory returned null interceptor");
      interceptor = this;
    }
    this.headInterceptor = interceptor;
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

  public WorkflowInterceptor getWorkflowInterceptor() {
    return headInterceptor;
  }

  @Override
  public <T> Promise<T> executeActivity(
      String activityName,
      Class<T> resultClass,
      Type resultType,
      Object[] args,
      ActivityOptions options) {
    RetryOptions retryOptions = options.getRetryOptions();
    // Replays a legacy history that used the client side retry correctly
    if (retryOptions != null && !context.isServerSideActivityRetry()) {
      return WorkflowRetryerInternal.retryAsync(
          retryOptions,
          () -> executeActivityOnce(activityName, options, args, resultClass, resultType));
    }
    return executeActivityOnce(activityName, options, args, resultClass, resultType);
  }

  private <T> Promise<T> executeActivityOnce(
      String name, ActivityOptions options, Object[] args, Class<T> returnClass, Type returnType) {
    byte[] input = converter.toData(args);
    Promise<byte[]> binaryResult = executeActivityOnce(name, options, input);
    if (returnClass == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply((r) -> converter.fromData(r, returnClass, returnType));
  }

  private Promise<byte[]> executeActivityOnce(String name, ActivityOptions options, byte[] input) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteActivityParameters params = constructExecuteActivityParameters(name, options, input);
    Consumer<Exception> cancellationCallback =
        context.scheduleActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CancellationException(reason));
              return null;
            });
    return callback.result;
  }

  private class ActivityCallback {
    private CompletablePromise<byte[]> result = Workflow.newPromise();

    public void invoke(byte[] output, Exception failure) {
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
    if (failure instanceof CancellationException) {
      return (CancellationException) failure;
    }
    if (failure instanceof ActivityTaskFailedException) {
      ActivityTaskFailedException taskFailed = (ActivityTaskFailedException) failure;
      String causeClassName = taskFailed.getReason();
      Class<? extends Exception> causeClass;
      Exception cause;
      try {
        @SuppressWarnings("unchecked") // cc is just to have a place to put this annotation
        Class<? extends Exception> cc = (Class<? extends Exception>) Class.forName(causeClassName);
        causeClass = cc;
        cause = getDataConverter().fromData(taskFailed.getDetails(), causeClass, causeClass);
      } catch (Exception e) {
        cause = e;
      }
      if (cause instanceof SimulatedTimeoutExceptionInternal) {
        // This exception is thrown only in unit tests to mock the activity timeouts
        SimulatedTimeoutExceptionInternal testTimeout = (SimulatedTimeoutExceptionInternal) cause;
        return new ActivityTimeoutException(
            taskFailed.getEventId(),
            taskFailed.getActivityType(),
            taskFailed.getActivityId(),
            testTimeout.getTimeoutType(),
            testTimeout.getDetails(),
            getDataConverter());
      }
      return new ActivityFailureException(
          taskFailed.getEventId(), taskFailed.getActivityType(), taskFailed.getActivityId(), cause);
    }
    if (failure instanceof ActivityTaskTimeoutException) {
      ActivityTaskTimeoutException timedOut = (ActivityTaskTimeoutException) failure;
      return new ActivityTimeoutException(
          timedOut.getEventId(),
          timedOut.getActivityType(),
          timedOut.getActivityId(),
          timedOut.getTimeoutType(),
          timedOut.getDetails(),
          getDataConverter());
    }
    if (failure instanceof ActivityException) {
      return (ActivityException) failure;
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
    if (options.getRetryOptions() != null) {
      options.getRetryOptions().validate();
    }

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
    byte[] input = converter.toData(args);
    Promise<byte[]> binaryResult = executeLocalActivityOnce(name, options, input, elapsed, attempt);
    if (returnClass == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply((r) -> converter.fromData(r, returnClass, returnType));
  }

  private Promise<byte[]> executeLocalActivityOnce(
      String name, LocalActivityOptions options, byte[] input, long elapsed, int attempt) {
    ActivityCallback callback = new ActivityCallback();
    ExecuteLocalActivityParameters params =
        constructExecuteLocalActivityParameters(name, options, input, elapsed, attempt);
    Consumer<Exception> cancellationCallback =
        context.scheduleLocalActivityTask(params, callback::invoke);
    CancellationScope.current()
        .getCancellationRequest()
        .thenApply(
            (reason) -> {
              cancellationCallback.accept(new CancellationException(reason));
              return null;
            });
    return callback.result;
  }

  private ExecuteActivityParameters constructExecuteActivityParameters(
      String name, ActivityOptions options, byte[] input) {
    ExecuteActivityParameters parameters = new ExecuteActivityParameters();
    // TODO: Real task list
    String taskList = options.getTaskList();
    if (taskList == null) {
      taskList = context.getTaskList();
    }
    parameters
        .withActivityType(new ActivityType().setName(name))
        .withInput(input)
        .withTaskList(taskList)
        .withScheduleToStartTimeoutSeconds(options.getScheduleToStartTimeout().getSeconds())
        .withStartToCloseTimeoutSeconds(options.getStartToCloseTimeout().getSeconds())
        .withScheduleToCloseTimeoutSeconds(options.getScheduleToCloseTimeout().getSeconds())
        .setHeartbeatTimeoutSeconds(options.getHeartbeatTimeout().getSeconds());
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      parameters.setRetryParameters(new RetryParameters(retryOptions));
    }
    return parameters;
  }

  private ExecuteLocalActivityParameters constructExecuteLocalActivityParameters(
      String name, LocalActivityOptions options, byte[] input, long elapsed, int attempt) {
    ExecuteLocalActivityParameters parameters = new ExecuteLocalActivityParameters();
    parameters
        .withActivityType(new ActivityType().setName(name))
        .withInput(input)
        .withScheduleToCloseTimeoutSeconds(options.getScheduleToCloseTimeout().getSeconds());
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      parameters.setRetryOptions(retryOptions);
    }
    parameters.setAttempt(attempt);
    parameters.setElapsedTime(elapsed);
    return parameters;
  }

  @Override
  public <R> WorkflowResult<R> executeChildWorkflow(
      String workflowType,
      Class<R> returnClass,
      Type returnType,
      Object[] args,
      ChildWorkflowOptions options) {
    byte[] input = converter.toData(args);
    CompletablePromise<WorkflowExecution> execution = Workflow.newPromise();
    Promise<byte[]> output = executeChildWorkflow(workflowType, options, input, execution);
    Promise<R> result = output.thenApply((b) -> converter.fromData(b, returnClass, returnType));
    return new WorkflowResult<>(result, execution);
  }

  private Promise<byte[]> executeChildWorkflow(
      String name,
      ChildWorkflowOptions options,
      byte[] input,
      CompletablePromise<WorkflowExecution> executionResult) {
    RetryOptions retryOptions = options.getRetryOptions();
    // This condition is for backwards compatibility with the code that
    // used client side retry before the server side retry existed.
    if (retryOptions != null && !context.isServerSideChildWorkflowRetry()) {
      ChildWorkflowOptions o1 =
          new ChildWorkflowOptions.Builder()
              .setTaskList(options.getTaskList())
              .setExecutionStartToCloseTimeout(options.getExecutionStartToCloseTimeout())
              .setTaskStartToCloseTimeout(options.getTaskStartToCloseTimeout())
              .setWorkflowId(options.getWorkflowId())
              .setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy())
              .setChildPolicy(options.getChildPolicy())
              .build();
      return WorkflowRetryerInternal.retryAsync(
          retryOptions, () -> executeChildWorkflowOnce(name, o1, input, executionResult));
    }
    return executeChildWorkflowOnce(name, options, input, executionResult);
  }

  /** @param executionResult promise that is set bu this method when child workflow is started. */
  private Promise<byte[]> executeChildWorkflowOnce(
      String name,
      ChildWorkflowOptions options,
      byte[] input,
      CompletablePromise<WorkflowExecution> executionResult) {
    RetryParameters retryParameters = null;
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      retryParameters = new RetryParameters(retryOptions);
    }
    StartChildWorkflowExecutionParameters parameters =
        new StartChildWorkflowExecutionParameters.Builder()
            .setWorkflowType(new WorkflowType().setName(name))
            .setWorkflowId(options.getWorkflowId())
            .setInput(input)
            .setChildPolicy(options.getChildPolicy())
            .setExecutionStartToCloseTimeoutSeconds(
                options.getExecutionStartToCloseTimeout().getSeconds())
            .setDomain(options.getDomain())
            .setTaskList(options.getTaskList())
            .setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeout().getSeconds())
            .setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy())
            .setRetryParameters(retryParameters)
            .setCronSchedule(options.getCronSchedule())
            .build();
    CompletablePromise<byte[]> result = Workflow.newPromise();
    Consumer<Exception> cancellationCallback =
        context.startChildWorkflow(
            parameters,
            executionResult::complete,
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
              cancellationCallback.accept(new CancellationException(reason));
              return null;
            });
    return result;
  }

  private RuntimeException mapChildWorkflowException(Exception failure) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof CancellationException) {
      return (CancellationException) failure;
    }
    if (failure instanceof ChildWorkflowException) {
      return (ChildWorkflowException) failure;
    }
    if (!(failure instanceof ChildWorkflowTaskFailedException)) {
      return new IllegalArgumentException("Unexpected exception type: ", failure);
    }
    ChildWorkflowTaskFailedException taskFailed = (ChildWorkflowTaskFailedException) failure;
    String causeClassName = taskFailed.getReason();
    Exception cause;
    try {
      @SuppressWarnings("unchecked")
      Class<? extends Exception> causeClass =
          (Class<? extends Exception>) Class.forName(causeClassName);
      cause = getDataConverter().fromData(taskFailed.getDetails(), causeClass, causeClass);
    } catch (Exception e) {
      cause = e;
    }
    if (cause instanceof SimulatedTimeoutExceptionInternal) {
      // This exception is thrown only in unit tests to mock the child workflow timeouts
      return new ChildWorkflowTimedOutException(
          taskFailed.getEventId(), taskFailed.getWorkflowExecution(), taskFailed.getWorkflowType());
    }
    return new ChildWorkflowFailureException(
        taskFailed.getEventId(),
        taskFailed.getWorkflowExecution(),
        taskFailed.getWorkflowType(),
        cause);
  }

  @Override
  public Promise<Void> newTimer(Duration delay) {
    Objects.requireNonNull(delay);
    long delaySeconds = roundUpToSeconds(delay).getSeconds();
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
              timer.completeExceptionally(new CancellationException(reason));
              return null;
            });
    return timer;
  }

  @Override
  public <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    DataConverter dataConverter = getDataConverter();
    byte[] result =
        context.sideEffect(
            () -> {
              R r = func.apply();
              return dataConverter.toData(r);
            });
    return dataConverter.fromData(result, resultClass, resultType);
  }

  @Override
  public <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    AtomicReference<R> unserializedResult = new AtomicReference<>();
    // As lambda below never returns Optional.empty() if there is a stored value
    // it is safe to call get on mutableSideEffect result.
    Optional<byte[]> optionalBytes =
        context.mutableSideEffect(
            id,
            converter,
            (storedBinary) -> {
              Optional<R> stored =
                  storedBinary.map((b) -> converter.fromData(b, resultClass, resultType));
              R funcResult =
                  Objects.requireNonNull(
                      func.apply(), "mutableSideEffect function " + "returned null");
              if (!stored.isPresent() || updated.test(stored.get(), funcResult)) {
                unserializedResult.set(funcResult);
                return Optional.of(converter.toData(funcResult));
              }
              return Optional.empty(); // returned only when value doesn't need to be updated
            });
    if (!optionalBytes.isPresent()) {
      throw new IllegalArgumentException(
          "No value found for mutableSideEffectId="
              + id
              + ", during replay it usually indicates a different workflow runId than the original one");
    }
    byte[] binaryResult = optionalBytes.get();
    // An optimization that avoids unnecessary deserialization of the result.
    R unserialized = unserializedResult.get();
    if (unserialized != null) {
      return unserialized;
    }
    return converter.fromData(binaryResult, resultClass, resultType);
  }

  @Override
  public int getVersion(String changeID, int minSupported, int maxSupported) {
    return context.getVersion(changeID, converter, minSupported, maxSupported);
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

  public byte[] query(String type, byte[] args) {
    Functions.Func1<byte[], byte[]> callback = queryCallbacks.get(type);
    if (callback == null) {
      throw new IllegalArgumentException(
          "Unknown query type: " + type + ", knownTypes=" + queryCallbacks.keySet());
    }
    return callback.apply(args);
  }

  @Override
  public void registerQuery(
      String queryType, Type[] argTypes, Functions.Func1<Object[], Object> callback) {
    //    if (queryCallbacks.containsKey(queryType)) {
    //      throw new IllegalStateException("Query \"" + queryType + "\" is already registered");
    //    }
    queryCallbacks.put(
        queryType,
        (input) -> {
          Object[] args = converter.fromDataArray(input, argTypes);
          Object result = callback.apply(args);
          return converter.toData(result);
        });
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
    byte[] input = getDataConverter().toData(args);
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
              cancellationCallback.accept(new CancellationException(reason));
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
    ContinueAsNewWorkflowExecutionParameters parameters =
        new ContinueAsNewWorkflowExecutionParameters();
    if (workflowType.isPresent()) {
      parameters.setWorkflowType(workflowType.get());
    }
    if (options.isPresent()) {
      ContinueAsNewOptions ops = options.get();
      parameters.setExecutionStartToCloseTimeoutSeconds(
          (int) ops.getExecutionStartToCloseTimeout().getSeconds());
      parameters.setTaskStartToCloseTimeoutSeconds(
          (int) ops.getTaskStartToCloseTimeout().getSeconds());
      parameters.setTaskList(ops.getTaskList());
    }
    parameters.setInput(getDataConverter().toData(args));
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
    if (failure instanceof CancellationException) {
      return (CancellationException) failure;
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
    if (lastCompletionResult == null || lastCompletionResult.length == 0) {
      return null;
    }

    DataConverter dataConverter = getDataConverter();
    return dataConverter.fromData(lastCompletionResult, resultClass, resultType);
  }
}
