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

import com.uber.cadence.ActivityType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.replay.ActivityTaskFailedException;
import com.uber.cadence.internal.replay.ActivityTaskTimeoutException;
import com.uber.cadence.internal.replay.ChildWorkflowTaskFailedException;
import com.uber.cadence.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.internal.replay.DecisionContext;
import com.uber.cadence.internal.replay.ExecuteActivityParameters;
import com.uber.cadence.internal.replay.SignalExternalWorkflowParameters;
import com.uber.cadence.internal.replay.StartChildWorkflowExecutionParameters;
import com.uber.cadence.workflow.ActivityException;
import com.uber.cadence.workflow.ActivityFailureException;
import com.uber.cadence.workflow.ActivityTimeoutException;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.ChildWorkflowException;
import com.uber.cadence.workflow.ChildWorkflowFailureException;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.SignalExternalWorkflowException;
import com.uber.cadence.workflow.Workflow;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SyncDecisionContext implements ActivityExecutor {
  private final DecisionContext context;
  private DeterministicRunner runner;
  private final DataConverter converter;
  private final WorkflowTimers timers = new WorkflowTimers();
  private Map<String, Functions.Func1<byte[], byte[]>> queryCallbacks = new HashMap<>();

  public SyncDecisionContext(DecisionContext context, DataConverter converter) {
    this.context = context;
    this.converter = converter;
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

  @Override
  public <T> Promise<T> executeActivity(
      String name, ActivityOptions options, Object[] args, Class<T> returnType) {
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      return WorkflowRetryerInternal.retryAsync(
          retryOptions, () -> executeActivityOnce(name, options, args, returnType));
    }
    return executeActivityOnce(name, options, args, returnType);
  }

  private <T> Promise<T> executeActivityOnce(
      String name, ActivityOptions options, Object[] args, Class<T> returnType) {
    byte[] input = converter.toData(args);
    Promise<byte[]> binaryResult = executeActivityOnce(name, options, input);
    if (returnType == Void.TYPE) {
      return binaryResult.thenApply((r) -> null);
    }
    return binaryResult.thenApply((r) -> converter.fromData(r, returnType));
  }

  private Promise<byte[]> executeActivityOnce(String name, ActivityOptions options, byte[] input) {
    CompletablePromise<byte[]> result = Workflow.newPromise();
    ExecuteActivityParameters parameters = new ExecuteActivityParameters();
    //TODO: Real task list
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
    Consumer<Exception> cancellationCallback =
        context.scheduleActivityTask(
            parameters,
            (output, failure) -> {
              if (failure != null) {
                runner.executeInWorkflowThread(
                    "activity failure callback",
                    () -> result.completeExceptionally(mapActivityException(failure)));
              } else {
                runner.executeInWorkflowThread(
                    "activity failure callback", () -> result.complete(output));
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
        cause = getDataConverter().fromData(taskFailed.getDetails(), causeClass);
      } catch (Exception e) {
        cause = e;
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

  public Promise<byte[]> executeChildWorkflow(
      String name,
      ChildWorkflowOptions options,
      byte[] input,
      CompletablePromise<WorkflowExecution> executionResult) {
    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      return WorkflowRetryerInternal.retryAsync(
          retryOptions, () -> executeChildWorkflowOnce(name, options, input, executionResult));
    }
    return executeChildWorkflowOnce(name, options, input, executionResult);
  }

  /** @param executionResult promise that is set bu this method when child workflow is started. */
  private Promise<byte[]> executeChildWorkflowOnce(
      String name,
      ChildWorkflowOptions options,
      byte[] input,
      CompletablePromise<WorkflowExecution> executionResult) {
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
      throw (ChildWorkflowException) failure;
    }
    if (!(failure instanceof ChildWorkflowTaskFailedException)) {
      throw new IllegalArgumentException("Unexpected exception type: ", failure);
    }
    ChildWorkflowTaskFailedException taskFailed = (ChildWorkflowTaskFailedException) failure;
    String causeClassName = taskFailed.getReason();
    Exception cause;
    try {
      @SuppressWarnings("unchecked")
      Class<? extends Exception> causeClass =
          (Class<? extends Exception>) Class.forName(causeClassName);
      cause = getDataConverter().fromData(taskFailed.getDetails(), causeClass);
    } catch (Exception e) {
      cause = e;
    }
    return new ChildWorkflowFailureException(
        taskFailed.getEventId(),
        taskFailed.getWorkflowExecution(),
        taskFailed.getWorkflowType(),
        cause);
  }

  public Promise<Void> newTimer(long delaySeconds) {
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

  public void fireTimers() {
    timers.fireTimers(context.currentTimeMillis());
  }

  public boolean hasTimersToFire() {
    return timers.hasTimersToFire(context.currentTimeMillis());
  }

  public long getNextFireTime() {
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

  private void registerQuery(String queryType, Functions.Func1<byte[], byte[]> callback) {
    Functions.Func1<byte[], byte[]> previous = queryCallbacks.put(queryType, callback);
    if (previous != null) {
      throw new IllegalStateException("Query " + queryType + " is already registered");
    }
  }

  public void registerQuery(Object queryImplementation) {
    POJOQueryImplementationFactory queryFactory =
        new POJOQueryImplementationFactory(converter, queryImplementation);
    Set<String> queries = queryFactory.getQueryFunctionNames();
    for (String query : queries) {
      registerQuery(query, queryFactory.getQueryFunction(query));
    }
  }

  public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters) {
    context.continueAsNewOnCompletion(parameters);
  }

  public DataConverter getDataConverter() {
    return converter;
  }

  public boolean isReplaying() {
    return context.isReplaying();
  }

  public DecisionContext getContext() {
    return context;
  }

  public Promise<Void> signalWorkflow(
      WorkflowExecution execution, String signalName, Object... args) {
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

  private RuntimeException mapSignalWorkflowException(Exception failure) {
    if (failure == null) {
      return null;
    }
    if (failure instanceof CancellationException) {
      return (CancellationException) failure;
    }

    if (!(failure instanceof SignalExternalWorkflowException)) {
      throw new IllegalArgumentException("Unexpected exception type: ", failure);
    }
    return (SignalExternalWorkflowException) failure;
  }
}
