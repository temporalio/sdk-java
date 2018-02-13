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
import com.uber.cadence.workflow.RFuture;
import com.uber.cadence.workflow.WFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WFutureImpl<V> implements WFuture<V> {

    private static class Handler {
        final WFutureImpl<Object> result;
        final Functions.Func2 function;

        private Handler(WFutureImpl<Object> result, Functions.Func2 function) {
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

    WFutureImpl() {
        runner = WorkflowThreadInternal.currentThreadInternal().getRunner();
    }

    @Override
    public boolean isDone() {
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

    private void unregisterWithRunner() {
        if (registeredWithRunner) {
            runner.forgetFailedFuture(this);
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
            runner.registerFailedFuture(this); // To ensure that failure is not ignored
            registeredWithRunner = true;
        }
        return true;
    }

    @Override
    public boolean completeFrom(RFuture<V> source) {
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

    public <U> WFutureImpl<U> thenApply(Functions.Func1<? super V, ? extends U> fn) {
        return handle((r, e) -> {
            if (e != null) {
                throw e;
            }
            return fn.apply(r);
        });
    }

    public <U> WFutureImpl<U> handle(Functions.Func2<? super V, RuntimeException, ? extends U> fn) {
        // TODO: Cancellation handler
        WFutureImpl<Object> resultFuture = new WFutureImpl<>();
        if (completed) {
            invokeHandler(fn, resultFuture);
            unregisterWithRunner();
        } else {
            handlers.add(new Handler(resultFuture, fn));
        }
        return (WFutureImpl<U>) resultFuture;
    }

    private void invokeHandler(Functions.Func2 fn, WFutureImpl<Object> resultFuture) {
        try {
            Object result = fn.apply(value, failure);
            resultFuture.complete(result);
        } catch (RuntimeException e) {
            resultFuture.completeExceptionally(e);
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
