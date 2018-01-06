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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SyncDecisionContext {
    private final AsyncDecisionContext context;
    private final GenericAsyncActivityClient activityClient;
    private final DataConverter converter;

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
