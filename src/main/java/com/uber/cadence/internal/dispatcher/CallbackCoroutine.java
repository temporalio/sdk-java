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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

class CallbackCoroutine implements DeterministicRunnerCoroutine {


    /**
     * Runnable passed to the thread that wraps a runnable passed to the WorkflowThreadImpl constructor.
     */
    class RunnableWrapper implements Runnable {

        private final Functions.Func<Boolean> r;
        private String originalName;
        private String name;
        private Boolean result;

        RunnableWrapper(String name, Functions.Func<Boolean> r) {
            this.name = name;
            this.r = r;
        }

        @Override
        public void run() {
            result = null;
            unhandledException = null;
            thread = Thread.currentThread();
            originalName = thread.getName();
            thread.setName(name);
            DeterministicRunnerImpl.setCurrentThreadInternal(CallbackCoroutine.this);
            try {
                result = r.apply();
            } catch (Throwable e) {
                unhandledException = e;
            } finally {
                DeterministicRunnerImpl.setCurrentThreadInternal(null);
                thread.setName(originalName);
                thread = null;
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
            if (thread != null) {
                thread.setName(name);
            }
        }

        boolean getResult() {
            if (result == null) {
                throw new IllegalStateException("Result is not ready", unhandledException);
            }
            return result;
        }
    }

    private final ExecutorService threadPool;
    private final DeterministicRunnerImpl runner;
    private final RunnableWrapper task;
    private final CancellationScopeImpl cancellationScope;
    private Thread thread;
    private Throwable unhandledException;
    private boolean stopped;

    /**
     * @param coroutineFunction        returns false if no progress was made.
     * @param ignoreParentCancellation
     */
    public CallbackCoroutine(ExecutorService threadPool, DeterministicRunnerImpl runner, String name,
                             Functions.Func<Boolean> coroutineFunction, boolean ignoreParentCancellation, CancellationScopeImpl parent) {
        this.threadPool = threadPool;
        this.runner = runner;
        // TODO: Use thread pool instead of creating new threads.
        if (name == null) {
            name = "workflow-callbacks-" + super.hashCode();
        }
        this.task = new RunnableWrapper(name, coroutineFunction);
        cancellationScope = new CancellationScopeImpl(ignoreParentCancellation, task, parent);

    }

    public DeterministicRunnerImpl getRunner() {
        return runner;
    }

    public SyncDecisionContext getDecisionContext() {
        return runner.getDecisionContext();
    }

    @Override
    public long getBlockedUntil() {
        return 0;
    }

    /**
     * @return true if coroutine made some progress.
     */
    @Override
    public boolean runUntilBlocked() {
        Future<?> taskFuture = threadPool.submit(cancellationScope::run);
        try {
            taskFuture.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        return task.getResult();
    }

    @Override
    public Throwable getUnhandledException() {
        return unhandledException;
    }

    @Override
    public boolean isDone() {
        return stopped;
    }

    /**
     * Interrupt coroutine by throwing DestroyWorkflowThreadError from a yield method
     * it is blocked on and wait for coroutine thread to finish execution.
     */
    public void stop() {
        stopped = true;
    }

    @Override
    public void addStackTrace(StringBuilder result) {
    }

    @Override
    public void yieldImpl(String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        throw new IllegalStateException("Blocking calls are not allowed in callback threads");
    }

    @Override
    public boolean yieldImpl(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        throw new IllegalStateException("Blocking calls are not allowed in callback threads");
    }
}
