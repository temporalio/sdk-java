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

import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.WorkflowFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

final class WorkflowFutureImpl<T> implements WorkflowFuture<T> {

    private static class Handler {
        final WorkflowFutureImpl<Object> result;
        final Functions.Func2 function;

        private Handler(WorkflowFutureImpl<Object> result, Functions.Func2 function) {
            this.result = result;
            this.function = function;
        }
    }

    private final BiConsumer<WorkflowFuture<T>, Boolean> cancellationHandler;
    private T value;
    private Exception failure;
    private boolean completed;
    private boolean cancelled;
    private final List<Handler> handlers = new ArrayList<>();
    private final DeterministicRunnerImpl runner;
    private boolean registeredWithRunner;

    WorkflowFutureImpl() {
        this((BiConsumer) null);
    }

    public WorkflowFutureImpl(BiConsumer<WorkflowFuture<T>, Boolean> cancellationHandler) {
        runner = WorkflowThreadInternal.currentThreadInternal().getRunner();
        this.cancellationHandler = cancellationHandler;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) {
            return false;
        }
        if (cancellationHandler != null) {
            // Ideally cancellationHandler completes this future
            cancellationHandler.accept(this, mayInterruptIfRunning);
        }
        if (!isDone()) {
            completeExceptionally(new CancellationException());
        }
        cancelled = true;
        return true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return completed;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        if (!completed) {
            WorkflowThreadInternal.yield("Feature.get", () -> completed);
        }
        if (failure != null) {
            unregisterWithRunner();
            throw new ExecutionException(failure);
        }
        return value;
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (!completed) {
            WorkflowThreadInternal.yield(unit.toMillis(timeout), "Feature.get", () -> completed);
        }
        if (!completed) {
            throw new TimeoutException();
        }
        if (failure != null) {
            unregisterWithRunner();
            throw new ExecutionException(failure);
        }
        return value;
    }

    private void unregisterWithRunner() {
        if (registeredWithRunner) {
            runner.forgetFailedFuture(this);
            registeredWithRunner = false;
        }
    }

    public boolean complete(T value) {
        if (completed) {
            return false;
        }
        this.completed = true;
        this.value = value;
        invokeHandlers();
        return true;
    }

    public boolean completeExceptionally(Exception value) {
        if (completed) {
            return false;
        }
        this.completed = true;
        this.failure = value;
        boolean invoked = invokeHandlers();
        if (!invoked) {
            runner.registerFailedFuture(this); // To ensure that failure is not ignored
            registeredWithRunner = true;
        }
        return true;
    }

    public <U> WorkflowFutureImpl<U> thenApply(Functions.Func1<? super T, ? extends U> fn) {
        return handle((r, e) -> {
            if (e != null) {
                if (e instanceof CompletionException) {
                    throw (CompletionException) e;
                }
                throw new CompletionException(e);
            }
            return fn.apply(r);
        });
    }

    public <U> WorkflowFutureImpl<U> handle(Functions.Func2<? super T, Exception, ? extends U> fn) {
        // TODO: Cancellation handler
        WorkflowFutureImpl<Object> resultFuture = new WorkflowFutureImpl<>();
        if (completed) {
            invokeHandler(fn, resultFuture);
            unregisterWithRunner();
        } else {
            handlers.add(new Handler(resultFuture, fn));
        }
        return (WorkflowFutureImpl<U>) resultFuture;
    }

    private void invokeHandler(Functions.Func2 fn, WorkflowFutureImpl<Object> resultFuture) {
        try {
            Object result = fn.apply(value, failure);
            resultFuture.complete(result);
        } catch (CompletionException e) {
            resultFuture.completeExceptionally(e);
        } catch (Exception e) {
            resultFuture.completeExceptionally(new CompletionException(e));
        }
    }

    /**
     * @return true if there were any handlers invoked
     */
    private boolean invokeHandlers() {
        for (Handler handler : handlers) {
            invokeHandler(handler.function, handler.result);
        }
        return !handlers.isEmpty();
    }
}
