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
import com.uber.cadence.workflow.WorkflowThread;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

class WorkflowThreadInternal implements WorkflowThread, DeterministicRunnerCoroutine {

    /**
     * Runnable passed to the thread that wraps a runnable passed to the WorkflowThreadImpl constructor.
     */
    class RunnableWrapper implements Runnable {

        private final WorkflowThreadContext context;
        private final Functions.Proc r;
        private String originalName;
        private String name;

        RunnableWrapper(WorkflowThreadContext context, String name, Functions.Proc r) {
            this.context = context;
            this.name = name;
            this.r = r;
            if (context.getStatus() != Status.CREATED) {
                throw new IllegalStateException("context not in CREATED state");
            }
        }

        @Override
        public void run() {
            thread = Thread.currentThread();
            originalName = thread.getName();
            thread.setName(name);
            currentThreadThreadLocal.set(WorkflowThreadInternal.this);
            try {
                // initialYield blocks thread until the first runUntilBlocked is called.
                // Otherwise r starts executing without control of the dispatcher.
                context.initialYield();
                r.apply();
            } catch (DestroyWorkflowThreadError e) {
                if (!context.destroyRequested()) {
                    context.setUnhandledException(e);
                }
            } catch (Throwable e) {
                context.setUnhandledException(e);
            } finally {
                context.setStatus(Status.DONE);
                thread.setName(originalName);
                thread = null;
                currentThreadThreadLocal.set(null);
            }
        }

        public String getName() {
            return name;
        }

        public StackTraceElement[] getStackTrace() {
            if (thread != null) {
                return thread.getStackTrace();
            }
            return new StackTraceElement[0];
        }

        public void setName(String name) {
            this.name = name;
            if (thread != null) {
                thread.setName(name);
            }
        }
    }

    static final ThreadLocal<WorkflowThreadInternal> currentThreadThreadLocal = new ThreadLocal<>();

    private final ExecutorService threadPool;
    private final WorkflowThreadContext context;
    private final DeterministicRunnerImpl runner;
    private final RunnableWrapper task;
    private Thread thread;
    private Future<?> taskFuture;

    /**
     * If not 0 then thread is blocked on a sleep (or on an operation with a timeout).
     * The value is the time in milliseconds (as in currentTimeMillis()) when thread will continue.
     * Note that thread still has to be called for evaluation as other threads might interrupt the blocking call.
     */
    private long blockedUntil;

    static WorkflowThreadInternal currentThreadInternal() {
        WorkflowThreadInternal result = currentThreadThreadLocal.get();
        if (result == null) {
            throw new IllegalStateException("Called from non workflow or workflow callback thread");
        }
        WorkflowThreadContext context = result.getContext();
        if (context.getStatus() != Status.RUNNING) {
            throw new IllegalStateException("Called from non running coroutine thread. Thread status is " + context.getStatus());
        }
        return result;
    }

    WorkflowThreadInternal(ExecutorService threadPool, DeterministicRunnerImpl runner, String name, Functions.Proc runnable) {
        this.threadPool = threadPool;
        this.runner = runner;
        this.context = new WorkflowThreadContext(runner.getLock());
        // TODO: Use thread pool instead of creating new threads.
        if (name == null) {
            name = "workflow-" + super.hashCode();
        }
        this.task = new RunnableWrapper(context, name, runnable);
    }

    static WorkflowThread newThread(Functions.Proc runnable) {
        return currentThreadInternal().getRunner().newThread(runnable);
    }

    static WorkflowThread newThread(Functions.Proc runnable, String name) {
        return currentThreadInternal().getRunner().newThread(runnable, name);
    }

    @Override
    public void interrupt() {
        context.interrupt();
    }

    @Override
    public boolean isInterrupted() {
        return context.isInterrupted();
    }

    public boolean resetInterrupted() {
        return context.resetInterrupted();
    }

    public boolean isDestroyRequested() {
        return context.destroyRequested();
    }

    public void start() {
        if (context.getStatus() != Status.CREATED) {
            throw new IllegalThreadStateException("already started");
        }
        taskFuture = threadPool.submit(task);
    }

    public WorkflowThreadContext getContext() {
        return context;
    }

    public DeterministicRunnerImpl getRunner() {
        return runner;
    }

    public SyncDecisionContext getDecisionContext() {
        return runner.getDecisionContext();
    }

    @Override
    public void join() throws InterruptedException {
        WorkflowThreadInternal.yield("WorkflowThread.join", () -> isDone());
    }

    // TODO: Timeout support
    @Override
    public void join(long millis) throws InterruptedException {
        WorkflowThreadInternal.yield(millis, "WorkflowThread.join", () -> isDone());
    }

    public boolean isAlive() {
        return !isDone();
    }

    public void setName(String name) {
        task.setName(name);
    }

    public String getName() {
        return task.getName();
    }

    public long getId() {
        return hashCode();
    }

    @Override
    public long getBlockedUntil() {
        return blockedUntil;
    }

    public void setBlockedUntil(long blockedUntil) {
        this.blockedUntil = blockedUntil;
    }

    /**
     * @return true if coroutine made some progress.
     */
    @Override
    public boolean runUntilBlocked() {
        if (taskFuture == null) {
            // Thread is not yet started
            return false;
        }
        boolean result = context.runUntilBlocked();
        return result;
    }

    public boolean isDone() {
        return context.isDone();
    }

    public Thread.State getState() {
        if (context.getStatus() == Status.YIELDED) {
            return Thread.State.BLOCKED;
        } else if (context.getStatus() == Status.DONE) {
            return Thread.State.TERMINATED;
        } else {
            return Thread.State.RUNNABLE;
        }
    }

    @Override
    public Throwable getUnhandledException() {
        return context.getUnhandledException();
    }

    /**
     * Evaluates function in the context of the coroutine without unblocking it.
     * Used to get current coroutine status, like stack trace.
     *
     * @param function Parameter is reason for current goroutine blockage.
     */
    public void evaluateInCoroutineContext(Consumer<String> function) {
        context.evaluateInCoroutineContext(function);
    }

    /**
     * Interrupt coroutine by throwing DestroyWorkflowThreadError from a yield method
     * it is blocked on and wait for coroutine thread to finish execution.
     */
    public void stop() {
        context.destroy();
        if (!context.isDone()) {
            throw new RuntimeException("Couldn't destroy the thread. " +
                    "The blocked thread stack trace: " + getStackTrace());
        }
        try {
            taskFuture.get();
        } catch (InterruptedException e) {
            throw new Error("Unexpected interrupt", e);
        } catch (ExecutionException e) {
            throw new Error("Unexpected failure stopping coroutine", e);
        }
    }

    /**
     * @return stack trace of the coroutine thread
     */
    @Override
    public String getStackTrace() {
        StackTraceElement[] st = task.getStackTrace();
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        for (StackTraceElement se : st) {
            pw.println("\tat " + se);
        }
        return sw.toString();
    }

    /**
     * Block current thread until unblockCondition is evaluated to true.
     * This method is intended for framework level libraries, never use directly in a workflow implementation.
     *
     * @param reason           reason for blocking
     * @param unblockCondition condition that should return true to indicate that thread should unblock.
     * @throws InterruptedException       if thread was interrupted.
     * @throws DestroyWorkflowThreadError if thread was asked to be destroyed.
     */
    static void yield(String reason, Supplier<Boolean> unblockCondition) throws InterruptedException, DestroyWorkflowThreadError {
        WorkflowThreadInternal.currentThreadInternal().getContext().yield(reason, unblockCondition);
    }

    /**
     * Block current thread until unblockCondition is evaluated to true or timeoutMillis passes.
     *
     * @return false if timed out.
     */
    static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws InterruptedException, DestroyWorkflowThreadError {
        if (timeoutMillis == 0) {
            return unblockCondition.get();
        }
        WorkflowThreadInternal current = WorkflowThreadInternal.currentThreadInternal();
        long blockedUntil = WorkflowInternal.currentTimeMillis() + timeoutMillis;
        current.setBlockedUntil(blockedUntil);
        YieldWithTimeoutCondition condition = new YieldWithTimeoutCondition(unblockCondition, blockedUntil);
        yield(reason, condition);
        return !condition.isTimedOut();
    }

    static class YieldWithTimeoutCondition implements Supplier<Boolean> {

        private final Supplier<Boolean> unblockCondition;
        private final long blockedUntil;
        private boolean timedOut;

        public YieldWithTimeoutCondition(Supplier<Boolean> unblockCondition, long blockedUntil) {
            this.unblockCondition = unblockCondition;
            this.blockedUntil = blockedUntil;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        /**
         * @return true if condition matched or timed out
         */
        @Override
        public Boolean get() {
            boolean result = unblockCondition.get();
            if (result) {
                return true;
            }
            timedOut = WorkflowInternal.currentTimeMillis() >= blockedUntil;
            return timedOut;
        }
    }
}
