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
import com.uber.cadence.workflow.Workflow;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AllOfPromise<G> implements Promise<List<G>> {

    private G[] result;
    private final CompletablePromise<List<G>> impl = Workflow.newPromise();

    private int notReadyCount;

    @SuppressWarnings("unchecked")
    AllOfPromise(Promise<G>[] promises) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[]) new Object[promises.length];
        int index = 0;
        for (Promise<G> f : promises) {
            addPromise(index, f);
            index++;
        }
    }

    @SuppressWarnings("unchecked")
    public AllOfPromise(Collection<Promise<G>> promises) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[]) new Object[promises.size()];
        int index = 0;
        for (Promise<G> f : promises) {
            addPromise(index, f);
            index++;
        }
    }

    private void addPromise(int index, Promise<G> f) {
        if (f.isCompleted()) {
            result[index] = f.get();
        } else {
            notReadyCount++;
            final int i = index;
            f.handle((r, e) -> {
                if (notReadyCount == 0) {
                    throw new Error("Unexpected 0 count");
                }
                if (impl.isCompleted()) {
                    return null;
                }
                if (e != null) {
                    impl.completeExceptionally(e);
                }
                result[i] = r;
                if (--notReadyCount == 0) {
                    impl.complete(Arrays.asList(result));
                }
                return null;
            });
        }
    }

    @Override
    public <U> Promise<U> thenApply(Functions.Func1<? super List<G>, ? extends U> fn) {
        return impl.thenApply(fn);
    }

    @Override
    public <U> Promise<U> handle(Functions.Func2<? super List<G>, RuntimeException, ? extends U> fn) {
        return impl.handle(fn);
    }

    @Override
    public <U> Promise<U> thenCompose(Functions.Func1<? super List<G>, ? extends Promise<U>> func) {
        return impl.thenCompose(func);
    }

    @Override
    public Promise<List<G>> exceptionally(Functions.Func1<Throwable, ? extends List<G>> fn) {
        return impl.exceptionally(fn);
    }

    @Override
    public boolean isCompleted() {
        return impl.isCompleted();
    }

    @Override
    public List<G> get() {
        return impl.get();
    }

    @Override
    public List<G> get(List<G> defaultValue) {
        return impl.get(defaultValue);
    }

    @Override
    public List<G> get(long timeout, TimeUnit unit) throws TimeoutException {
        return impl.get(timeout, unit);
    }

    @Override
    public List<G> get(long timeout, TimeUnit unit, List<G> defaultValue) {
        return impl.get(timeout, unit, defaultValue);
    }
}
