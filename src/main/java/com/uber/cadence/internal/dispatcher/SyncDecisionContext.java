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
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.StartChildWorkflowExecutionParameters;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowContext;
import com.uber.cadence.workflow.WorkflowFuture;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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

    public <T> T executeActivity(String name, ActivitySchedulingOptions options, Object[] args, Class<T> returnType) {
        byte[] input = converter.toData(args);
        byte[] result = executeActivity(name, options, input);
        return converter.fromData(result, returnType);
    }

    public <T> WorkflowFuture<T> executeActivityAsync(String name, ActivitySchedulingOptions options, Object[] args, Class<T> returnType) {
        byte[] input = converter.toData(args);
        WorkflowFuture<byte[]> binaryResult = executeActivityAsync(name, options, input);
        if (returnType == Void.TYPE) {
            return binaryResult.thenApply(r -> null);
        }
        return binaryResult.thenApply(r -> converter.fromData(r, returnType));
    }

    public byte[] executeActivity(String name, ActivitySchedulingOptions options, byte[] input) {
        Future<byte[]> result = executeActivityAsync(name, options, input);
        // TODO: Exception mapping
        try {
            return result.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public WorkflowFuture<byte[]> executeActivityAsync(String name, ActivitySchedulingOptions options, byte[] input) {
        ActivityFutureCancellationHandler cancellationHandler = new ActivityFutureCancellationHandler<>();
        WorkflowFuture<byte[]> result = new WorkflowFutureImpl<>(cancellationHandler);
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
                        result.completeExceptionally((Exception) failure);
                    } else {
                        result.complete(output);
                    }
                });
        cancellationHandler.setCancellationCallback(cancellationCallback);
        return result;
    }

    // TODO: Child workflow cancellation

    /**
     * @param executionResult future that is set bu this method when child workflow is started.
     */
    public WorkflowFuture<byte[]> executeChildWorkflow(
            String name, StartWorkflowOptions options, byte[] input, WorkflowFuture<WorkflowExecution> executionResult) {
//        ActivityFutureCancellationHandler cancellationHandler = new ActivityFutureCancellationHandler<>();
//        WorkflowFuture<byte[]> result = new WorkflowFutureImpl<>(cancellationHandler);
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
        WorkflowFuture<byte[]> result = Workflow.newFuture();
        Consumer<Throwable> cancellationCallback = workflowClient.startChildWorkflow(parameters,
                (execution) -> executionResult.complete(execution),
                (output, failure) -> {
                    if (failure != null) {
                        // TODO: Make sure that only Exceptions are passed into the callback.
                        result.completeExceptionally((Exception) failure);
                    } else {
                        result.complete(output);
                    }
                });
//        cancellationHandler.setCancellationCallback(cancellationCallback);
        return result;
    }

    public WorkflowFuture<Void> newTimer(long delaySeconds) {
        ActivityFutureCancellationHandler<Void> cancellationHandler = new ActivityFutureCancellationHandler<>();
        WorkflowFuture<Void> timer = new WorkflowFutureImpl<>(cancellationHandler);
        long fireTime = context.getWorkflowClock().currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
        timers.addTimer(fireTime, timer);
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

    public byte[] query(String type, byte[] args) throws Exception {
        Functions.Func1<byte[], byte[]> callback = queryCallbacks.get(type);
        if (callback == null) {
            throw new IllegalArgumentException("Unknown query type: " + type + ", knownTypes=" + queryCallbacks.keySet());
        }
        return callback.apply(args);
    }

    public void registerQuery(String queryType, Functions.Func1<byte[], byte[]> callback) {
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

    private static class ActivityFutureCancellationHandler<T> implements BiConsumer<WorkflowFuture<T>, Boolean> {
        private Consumer<Throwable> cancellationCallback;

        public void setCancellationCallback(Consumer<Throwable> cancellationCallback) {
            this.cancellationCallback = cancellationCallback;
        }

        @Override
        public void accept(WorkflowFuture<T> workflowFuture, Boolean aBoolean) {
            cancellationCallback.accept(new CancellationException("result future cancelled"));
        }
    }
}
