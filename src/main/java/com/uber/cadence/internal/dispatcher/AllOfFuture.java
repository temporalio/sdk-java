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
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AllOfFuture<G> implements WorkflowFuture<List<G>> {

    private G[] result;
    private final WorkflowFuture<List<G>> impl = Workflow.newFuture();

    private int notReadyCount;

    public AllOfFuture(WorkflowFuture<G>[] futures) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[])new Object[futures.length];
        int index = 0;
        for (WorkflowFuture<G> f : futures) {
            addFuture(index, f);
            index++;
        }
    }

    public AllOfFuture(Collection<WorkflowFuture<G>> futures) {
        // Using array to initialize it to the desired size with nulls.
        result = (G[])new Object[futures.size()];
        int index = 0;
        for (WorkflowFuture<G> f : futures) {
            addFuture(index, f);
            index++;
        }
    }

    private void addFuture(int index, WorkflowFuture<G> f) {
        if (f.isDone()) {
            try {
                result[index] = f.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        } else {
            notReadyCount++;
            final int i = index;
            f.handle((r, e) -> {
                if (notReadyCount == 0) {
                    throw new Exception("Unexpected 0 count");
                }
                if (impl.isDone()) {
                    return null;
                }
                if (e != null) {
                    impl.completeExceptionally(e);
                }
                result[i] =  r;
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
    public boolean completeExceptionally(Exception value) {
        return impl.completeExceptionally(value);
    }

    @Override
    public <U> WorkflowFuture<U> thenApply(Functions.Func1<? super List<G>, ? extends U> fn) {
        return impl.thenApply(fn);
    }

    @Override
    public <U> WorkflowFuture<U> handle(Functions.Func2<? super List<G>, Exception, ? extends U> fn) {
        return impl.handle(fn);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return impl.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return impl.isCancelled();
    }

    @Override
    public boolean isDone() {
        return impl.isDone();
    }

    @Override
    public List<G> get() throws InterruptedException, ExecutionException {
        return impl.get();
    }

    @Override
    public List<G> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return impl.get(timeout, unit);
    }
}
