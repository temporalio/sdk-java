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

import com.uber.cadence.AsyncDecisionContext;
import com.uber.cadence.DataConverter;
import com.uber.cadence.generic.ExecuteActivityParameters;
import com.uber.cadence.generic.GenericAsyncActivityClient;
import com.uber.cadence.ActivityType;
import com.uber.cadence.worker.POJOQueryImplementationFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SyncDecisionContext {
    private final AsyncDecisionContext context;
    private final GenericAsyncActivityClient activityClient;
    private final DataConverter converter;
    private final WorkflowTimers timers = new WorkflowTimers();
    private Map<String, WorkflowQueue<byte[]>> signalQueues = new HashMap<>();
    private Map<String, Functions.Func1<byte[], byte[]>> queryCallbacks = new HashMap<>();

    public SyncDecisionContext(AsyncDecisionContext context, DataConverter converter) {
        this.context = context;
        activityClient = context.getActivityClient();
        this.converter = converter;
    }

    public <T> T executeActivity(String name, Object[] args, Class<T> returnType) {
        byte[] input = converter.toData(args);
        byte[] result = executeActivity(name, input);
        return converter.fromData(result, returnType);
    }

    public <T> WorkflowFuture<T> executeActivityAsync(String name, Object[] args, Class<T> returnType) {
        byte[] input = converter.toData(args);
        WorkflowFuture<byte[]> binaryResult = executeActivityAsync(name, input);
        if (returnType == Void.TYPE) {
            return binaryResult.thenApply(r -> null);
        }
        return binaryResult.thenApply(r -> converter.fromData(r, returnType));
    }

    public byte[] executeActivity(String name, byte[] input) {
        Future<byte[]> result = executeActivityAsync(name, input);
        // TODO: Exception mapping
        try {
            return result.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public WorkflowFuture<byte[]> executeActivityAsync(String name, byte[] input) {
        ActivityFutureCancellationHandler cancellationHandler = new ActivityFutureCancellationHandler();
        WorkflowFuture<byte[]> result = new WorkflowFuture(cancellationHandler);
        ExecuteActivityParameters parameters = new ExecuteActivityParameters();
        //TODO: Real task list
        parameters.withActivityType(new ActivityType().setName(name)).
                withInput(input).
                withTaskList(context.getWorkflowContext().getTaskList()).
                withScheduleToStartTimeoutSeconds(10).
                withStartToCloseTimeoutSeconds(10).
                withScheduleToCloseTimeoutSeconds(30);
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

    public WorkflowFuture<Void> newTimer(long delaySeconds) {
        ActivityFutureCancellationHandler cancellationHandler = new ActivityFutureCancellationHandler();
        WorkflowFuture<Void> timer = new WorkflowFuture(cancellationHandler);
        long fireTime = context.getWorkflowClock().currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
        timers.addTimer(fireTime, timer);
        return timer;
    }

    public void fireTimers() {
        timers.fireTimers(context.getWorkflowClock().currentTimeMillis());
    }

    public long getNextFireTime() {
        return timers.getNextFireTime();
    }

    public <E> QueueConsumer<E> getSignalQueue(String signalName, Class<E> signalClass) {
        WorkflowQueue<byte[]> queue = getSignalQueue(signalName);
        return queue.map((serialized) -> converter.fromData(serialized, signalClass));
    }

    private WorkflowQueue<byte[]> getSignalQueue(String signalName) {
        WorkflowQueue<byte[]> queue = signalQueues.get(signalName);
        if (queue == null) {
            queue = Workflow.newQueue(Integer.MAX_VALUE);
            signalQueues.put(signalName, queue);
        }
        return queue;
    }

    public void processSignal(String signalName, byte[] input) {
        WorkflowQueue<byte[]> queue = getSignalQueue(signalName);
        try {
            queue.put(input);
        } catch (InterruptedException e) {
            throw new Error("unexpected", e);
        }
    }

    public byte[] query(String type, byte[] args) throws Exception {
        Functions.Func1<byte[], byte[]> callback = queryCallbacks.get(type);
        if (callback == null) {
            throw new IllegalArgumentException("Unknown query type: " + type);
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
        for (String query: queries) {
            registerQuery(query, queryFactory.getQueryFunction(query));
        }
    }

    private static class ActivityFutureCancellationHandler implements BiConsumer<WorkflowFuture, Boolean> {
        private Consumer<Throwable> cancellationCallback;

        public void setCancellationCallback(Consumer<Throwable> cancellationCallback) {
            this.cancellationCallback = cancellationCallback;
        }

        @Override
        public void accept(WorkflowFuture workflowFuture, Boolean aBoolean) {
            cancellationCallback.accept(new CancellationException("result future cancelled"));
        }
    }
}
