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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;
import java.util.function.Supplier;

class WorkflowThreadImpl implements WorkflowThread {

    /**
     * Runnable passed to the thread that wraps a runnable passed to the WorkflowThreadImpl constructor.
     */
    class RunnableWrapper implements Runnable {

        private final WorkflowThreadContext context;
        private final Runnable r;

        RunnableWrapper(WorkflowThreadContext context, Runnable r) {
            this.context = context;
            this.r = r;
            if (context.getStatus() != Status.CREATED) {
                throw new IllegalStateException("context not in CREATED state");
            }
        }

        @Override
        public void run() {
            currentThreadThreadLocal.set(WorkflowThreadImpl.this);
            try {
                // initialYield blocks thread until the first runUntilBlocked is called.
                // Otherwise r starts executing without control of the dispatcher.
                context.initialYield();
                r.run();
            } catch (DestroyWorkflowThreadError e) {
                if (!context.destroyRequested()) {
                    context.setUnhandledException(e);
                }
            } catch (Throwable e) {
                context.setUnhandledException(e);
            } finally {
                context.setStatus(Status.DONE);
            }
        }
    }

    static final ThreadLocal<WorkflowThreadImpl> currentThreadThreadLocal = new ThreadLocal<>();

    private final Thread thread;
    private final WorkflowThreadContext context;
    private final DeterministicRunnerImpl runner;

    /**
     * If not 0 then thread is blocked on a sleep (or on an operation with a timeout).
     * The value is the time in milliseconds (as in currentTimeMillis()) when thread will continue.
     * Note that thread still has to be called for evaluation as other threads might interrupt the blocking call.
     */
    private long blockedUntil;

    static WorkflowThreadImpl currentThread() {
        WorkflowThreadImpl result = currentThreadThreadLocal.get();
        if (result == null) {
            throw new IllegalStateException("Called from non coroutine thread");
        }
        WorkflowThreadContext context = result.getContext();
        if (context.getStatus() != Status.RUNNING) {
            throw new IllegalStateException("Called from non running coroutine thread");
        }
        return result;
    }

    public WorkflowThreadImpl(DeterministicRunnerImpl runner, String name, Runnable runnable) {
        this.runner = runner;
        this.context = new WorkflowThreadContext(runner.getLock());
        RunnableWrapper cr = new RunnableWrapper(context, runnable);
        // TODO: Use thread pool instead of creating new threads.
        if (name == null) {
            name = "workflow-" + super.hashCode();
        }
        this.thread = new Thread(cr, name);
    }

    public static WorkflowThread newThread(Runnable runnable) {
        return currentThread().getRunner().newThread(runnable);
    }

    public static WorkflowThread newThread(Runnable runnable, String name) {
        return currentThread().getRunner().newThread(runnable, name);
    }

    public void interrupt() {
        context.interrupt();
    }

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
        thread.start();
    }

    public WorkflowThreadContext getContext() {
        return context;
    }

    public DeterministicRunnerImpl getRunner() {
        return runner;
    }

    public SyncDecisionContext getDecisionContext() { return runner.getDecisionContext(); }

    @Override
    public void join() throws InterruptedException {
        WorkflowThreadImpl.yield("WorkflowThread.join", () -> isDone());
    }

    // TODO: Timeout support
    @Override
    public void join(long millis) throws InterruptedException {
        WorkflowThreadImpl.yield(millis, "WorkflowThread.join", () -> isDone());
    }

    public boolean isAlive() {
        return thread.isAlive() && !isDone();
    }

    public void setName(String name) {
        thread.setName(name);
    }

    public String getName() {
        return thread.getName();
    }

    public long getId() {
        return thread.getId();
    }

    public long getBlockedUntil() {
        return blockedUntil;
    }

    public void setBlockedUntil(long blockedUntil) {
        this.blockedUntil = blockedUntil;
    }

    public Thread getThread() {
        return thread;
    }

    /**
     * @return true if coroutine made some progress.
     */
    public boolean runUntilBlocked() {
        if (thread.getState() == Thread.State.NEW) {
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
        } else {
            return Thread.State.RUNNABLE;
        }
    }

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
            thread.join();
        } catch (InterruptedException e) {
            throw new Error("Unexpected interrupt", e);
        }
    }

    /**
     * @return stack trace of the coroutine thread
     */
    public String getStackTrace() {
        StackTraceElement[] st = thread.getStackTrace();
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
        WorkflowThreadImpl.currentThread().getContext().yield(reason, unblockCondition);
    }

    /**
     * Block current thread until unblockCondition is evaluated to true or timeoutMillis passes.
     *
     * @return false if timed out.
     */
    static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws InterruptedException, DestroyWorkflowThreadError {
        WorkflowThreadImpl current = WorkflowThreadImpl.currentThread();
        long blockedUntil = Workflow.currentTimeMillis() + timeoutMillis;
        current.setBlockedUntil(blockedUntil);
        YieldWithTimeoutCondition condition = new YieldWithTimeoutCondition(unblockCondition, blockedUntil);
        yield("reason", condition);
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

        @Override
        public Boolean get() {
            boolean result = unblockCondition.get();
            if (result) {
                return true;
            }
            result = Workflow.currentTimeMillis() >= blockedUntil;
            if (result) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }
}
