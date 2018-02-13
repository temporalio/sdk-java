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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.ActivityType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.internal.AsyncDecisionContext;
import com.uber.cadence.internal.DataConverter;
import com.uber.cadence.internal.StartWorkflowOptions;
import com.uber.cadence.internal.generic.ExecuteActivityParameters;
import com.uber.cadence.internal.generic.GenericAsyncActivityClient;
import com.uber.cadence.internal.generic.GenericAsyncWorkflowClient;
import com.uber.cadence.internal.worker.POJOQueryImplementationFactory;
import com.uber.cadence.workflow.ActivitySchedulingOptions;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.StartChildWorkflowExecutionParameters;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class SyncDecisionContext {
    private final AsyncDecisionContext context;
    private final GenericAsyncActivityClient activityClient;
    private final GenericAsyncWorkflowClient workflowClient;
    private final DataConverter converter;
    private final WorkflowTimers timers = new WorkflowTimers();
    private Map<String, Functions.Func1<byte[], byte[]>> queryCallbacks = new HashMap<>();

    public SyncDecisionContext(AsyncDecisionContext context, DataConverter converter) {
        this.context = context;
        activityClient = context.getActivityClient();
        workflowClient = context.getWorkflowClient();
        this.converter = converter;
    }

    public <T> Promise<T> executeActivity(String name, ActivitySchedulingOptions options, Object[] args, Class<T> returnType) {
        byte[] input = converter.toData(args);
        Promise<byte[]> binaryResult = executeActivity(name, options, input);
        if (returnType == Void.TYPE) {
            return binaryResult.thenApply(r -> null);
        }
        return binaryResult.thenApply(r -> converter.fromData(r, returnType));
    }

    private Promise<byte[]> executeActivity(String name, ActivitySchedulingOptions options, byte[] input) {
        CompletablePromise<byte[]> result = Workflow.newCompletablePromise();
        ExecuteActivityParameters parameters = new ExecuteActivityParameters();
        //TODO: Real task list
        parameters.withActivityType(new ActivityType().setName(name)).
                withInput(input).
                withTaskList(options.getTaskList()).
                withScheduleToStartTimeoutSeconds(options.getScheduleToStartTimeoutSeconds()).
                withStartToCloseTimeoutSeconds(options.getStartToCloseTimeoutSeconds()).
                withScheduleToCloseTimeoutSeconds(options.getScheduleToCloseTimeoutSeconds()).
                setHeartbeatTimeoutSeconds(options.getHeartbeatTimeoutSeconds());
        Consumer<Throwable> cancellationCallback = activityClient.scheduleActivityTask(parameters,
                (output, failure) -> {
                    if (failure != null) {
                        // TODO: Make sure that only Exceptions are passed into the callback.
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(output);
                    }
                });
        CancellationScope.current().getCancellationRequest().thenApply((reason) ->
        {
            cancellationCallback.accept(new CancellationException(reason));
            return null;
        });
        return result;
    }

    // TODO: Child workflow cancellation

    /**
     * @param executionResult promise that is set bu this method when child workflow is started.
     */
    public Promise<byte[]> executeChildWorkflow(
            String name, StartWorkflowOptions options, byte[] input, CompletablePromise<WorkflowExecution> executionResult) {
        StartChildWorkflowExecutionParameters parameters = new StartChildWorkflowExecutionParameters();
        parameters.withWorkflowType(new WorkflowType().setName(name)).withInput(input);
        if (options != null) {
            parameters.withTaskList(options.getTaskList()).withWorkflowId(options.getWorkflowId());
            if (options.getExecutionStartToCloseTimeoutSeconds() != null) {
                parameters.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
            }
            if (options.getTaskStartToCloseTimeoutSeconds() != null) {
                parameters.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
            }
        }
        CompletablePromise<byte[]> result = Workflow.newCompletablePromise();
        Consumer<Throwable> cancellationCallback = workflowClient.startChildWorkflow(parameters,
                executionResult::complete,
                (output, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else {
                        result.complete(output);
                    }
                });
        CancellationScope.current().getCancellationRequest().thenApply((reason) ->
        {
            cancellationCallback.accept(new CancellationException(reason));
            return null;
        });
        return result;
    }

    public Promise<Void> newTimer(long delaySeconds) {
        CompletablePromise<Void> timer = Workflow.newCompletablePromise();
        long fireTime = context.getWorkflowClock().currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
        timers.addTimer(fireTime, timer);
        CancellationScope.current().getCancellationRequest().thenApply((reason) ->
        {
            timers.removeTimer(fireTime, timer);
            timer.completeExceptionally(new CancellationException(reason));
            return null;
        });
        return timer;
    }

    /**
     * @return true if any timer fired
     */
    public boolean fireTimers() {
        return timers.fireTimers(context.getWorkflowClock().currentTimeMillis());
    }

    public long getNextFireTime() {
        return timers.getNextFireTime();
    }

    public byte[] query(String type, byte[] args) {
        Functions.Func1<byte[], byte[]> callback = queryCallbacks.get(type);
        if (callback == null) {
            throw new IllegalArgumentException("Unknown query type: " + type + ", knownTypes=" + queryCallbacks.keySet());
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
        POJOQueryImplementationFactory queryFactory = new POJOQueryImplementationFactory(converter, queryImplementation);
        Set<String> queries = queryFactory.getQueryFunctionNames();
        for (String query : queries) {
            registerQuery(query, queryFactory.getQueryFunction(query));
        }
    }

    public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters) {
        context.getWorkflowClient().continueAsNewOnCompletion(parameters);
    }

    public DataConverter getDataConverter() {
        return converter;
    }

    public WorkflowContext getWorkflowContext() {
        return context.getWorkflowContext();
    }
}
