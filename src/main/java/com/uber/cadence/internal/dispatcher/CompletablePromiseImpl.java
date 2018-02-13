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

import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class CompletablePromiseImpl<V> implements CompletablePromise<V> {

    private static class Handler {
        final CompletablePromiseImpl<Object> result;
        final Functions.Func2 function;

        private Handler(CompletablePromiseImpl<Object> result, Functions.Func2 function) {
            this.result = result;
            this.function = function;
        }
    }

    private V value;
    private RuntimeException failure;
    private boolean completed;
    private final List<Handler> handlers = new ArrayList<>();
    private final DeterministicRunnerImpl runner;
    private boolean registeredWithRunner;

    CompletablePromiseImpl() {
        runner = WorkflowThreadInternal.currentThreadInternal().getRunner();
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public V get() {
        if (!completed) {
            WorkflowThreadInternal.yield("Feature.get", () -> completed);
        }
        if (failure != null) {
            unregisterWithRunner();
            throw failure;
        }
        return value;
    }

    @Override
    public V get(V defaultValue) {
        if (!completed) {
            WorkflowThreadInternal.yield("Feature.get", () -> completed);
        }
        if (failure != null) {
            unregisterWithRunner();
            return defaultValue;
        }
        return value;
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws TimeoutException {
        if (!completed) {
            WorkflowThreadInternal.yield(unit.toMillis(timeout), "Feature.get", () -> completed);
        }
        if (!completed) {
            throw new TimeoutException();
        }
        if (failure != null) {
            unregisterWithRunner();
            throw failure;
        }
        return value;
    }

    @Override
    public V get(long timeout, TimeUnit unit, V defaultValue) {
        if (!completed) {
            WorkflowThreadInternal.yield(unit.toMillis(timeout), "Feature.get", () -> completed);
        }
        if (!completed) {
            return defaultValue;
        }
        if (failure != null) {
            unregisterWithRunner();
            return defaultValue;
        }
        return value;
    }

    private void unregisterWithRunner() {
        if (registeredWithRunner) {
            runner.forgetFailedPromise(this);
            registeredWithRunner = false;
        }
    }

    public boolean complete(V value) {
        if (completed) {
            return false;
        }
        this.completed = true;
        this.value = value;
        invokeHandlers();
        return true;
    }

    public boolean completeExceptionally(RuntimeException value) {
        if (completed) {
            return false;
        }
        this.completed = true;
        this.failure = value;
        boolean invoked = invokeHandlers();
        if (!invoked) {
            runner.registerFailedPromise(this); // To ensure that failure is not ignored
            registeredWithRunner = true;
        }
        return true;
    }

    @Override
    public boolean completeFrom(Promise<V> source) {
        if (completed) {
            return false;
        }
        source.handle((value, failure) -> {
            if (failure != null) {
                this.completeExceptionally(failure);
            } else {
                this.complete(value);
            }
            return null;
        });
        return true;
    }

    public <U> Promise<U> thenApply(Functions.Func1<? super V, ? extends U> fn) {
        return handle((r, e) -> {
            if (e != null) {
                throw e;
            }
            return fn.apply(r);
        });
    }

    public <U> Promise<U> handle(Functions.Func2<? super V, RuntimeException, ? extends U> fn) {
        // TODO: Cancellation handler
        CompletablePromiseImpl<Object> resultPromise = new CompletablePromiseImpl<>();
        if (completed) {
            invokeHandler(fn, resultPromise);
            unregisterWithRunner();
        } else {
            handlers.add(new Handler(resultPromise, fn));
        }
        return (Promise<U>) resultPromise;
    }

    private void invokeHandler(Functions.Func2 fn, CompletablePromiseImpl<Object> resultPromise) {
        try {
            Object result = fn.apply(value, failure);
            resultPromise.complete(result);
        } catch (RuntimeException e) {
            resultPromise.completeExceptionally(e);
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
