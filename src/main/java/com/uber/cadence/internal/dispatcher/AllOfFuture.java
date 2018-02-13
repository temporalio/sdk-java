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
import com.uber.cadence.workflow.Workflow;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AllOfFuture<G> implements WFuture<List<G>> {

    private G[] result;
    private final WFuture<List<G>> impl = Workflow.newFuture();

    private int notReadyCount;

    public AllOfFuture(WFuture<G>[] futures) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[]) new Object[futures.length];
        int index = 0;
        for (WFuture<G> f : futures) {
            addFuture(index, f);
            index++;
        }
    }

    public AllOfFuture(Collection<WFuture<G>> futures) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[]) new Object[futures.size()];
        int index = 0;
        for (WFuture<G> f : futures) {
            addFuture(index, f);
            index++;
        }
    }

    private void addFuture(int index, WFuture<G> f) {
        if (f.isDone()) {
            result[index] = f.get();
        } else {
            notReadyCount++;
            final int i = index;
            f.handle((r, e) -> {
                if (notReadyCount == 0) {
                    throw new Error("Unexpected 0 count");
                }
                if (impl.isDone()) {
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
    public boolean complete(List<G> value) {
        return impl.complete(value);
    }

    @Override
    public boolean completeExceptionally(RuntimeException value) {
        return impl.completeExceptionally(value);
    }

    @Override
    public boolean completeFrom(RFuture<List<G>> source) {
        return impl.completeFrom(source);
    }

    @Override
    public <U> WFuture<U> thenApply(Functions.Func1<? super List<G>, ? extends U> fn) {
        return impl.thenApply(fn);
    }

    @Override
    public <U> WFuture<U> handle(Functions.Func2<? super List<G>, RuntimeException, ? extends U> fn) {
        return impl.handle(fn);
    }

    @Override
    public boolean isDone() {
        return impl.isDone();
    }

    @Override
    public List<G> get() {
        return impl.get();
    }

    @Override
    public List<G> get(long timeout, TimeUnit unit) throws TimeoutException {
        return impl.get(timeout, unit);
    }
}
