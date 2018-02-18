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

import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.WorkflowThread;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CancellationException;
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
        private String originalName;
        private String name;
        private CancellationScopeImpl cancellationScope;

        RunnableWrapper(WorkflowThreadContext context, String name, boolean ignoreParentCancellation,
                        Runnable runnable) {
            this.context = context;
            this.name = name;
            cancellationScope = new CancellationScopeImpl(ignoreParentCancellation, runnable);
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
                log.debug(String.format("Workflow thread \"%s\" run started", name));
                cancellationScope.run();
                log.debug(String.format("Workflow thread \"%s\" run completed", name));
            } catch (DestroyWorkflowThreadError e) {
                if (!context.isDestroyRequested()) {
                    context.setUnhandledException(e);
                }
            } catch (Error e) {
                // Error aborts decision, not fail a workflow.
                if (log.isDebugEnabled()) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw, true);
                    e.printStackTrace(pw);
                    String stackTrace = sw.getBuffer().toString();
                    log.debug(String.format("Workflow thread \"%s\" run failed with Error:\n%s", name, stackTrace));
                }
                throw e;
            } catch (CancellationException e) {
                log.debug(String.format("Workflow thread \"%s\" run cancelled", name));
            } catch (Throwable e) {
                if (log.isWarnEnabled() && !root) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw, true);
                    e.printStackTrace(pw);
                    String stackTrace = sw.getBuffer().toString();
                    log.warn(String.format("Workflow thread \"%s\" run failed with unhandled exception:\n%s", name, stackTrace));
                }
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

        StackTraceElement[] getStackTrace() {
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

    private static final ThreadLocal<WorkflowThreadInternal> currentThreadThreadLocal = new ThreadLocal<>();
    private static final Log log = LogFactory.getLog(WorkflowThreadInternal.class);

    private final boolean root;
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

    WorkflowThreadInternal(boolean root, ExecutorService threadPool, DeterministicRunnerImpl runner, String name, boolean ignoreParentCancellation, Runnable runnable) {
        this.root = root;
        this.threadPool = threadPool;
        this.runner = runner;
        this.context = new WorkflowThreadContext(runner.getLock());
        // TODO: Use thread pool instead of creating new threads.
        if (name == null) {
            name = "workflow-" + super.hashCode();
        }
        log.debug(String.format("Workflow thread \"%s\" created", name));
        this.task = new RunnableWrapper(context, name, ignoreParentCancellation, runnable);
    }

    static WorkflowThread newThread(Runnable runnable, boolean ignoreParentCancellation) {
        return newThread(runnable, ignoreParentCancellation, null);
    }

    static WorkflowThread newThread(Runnable runnable, boolean ignoreParentCancellation, String name) {
        return currentThreadInternal().getRunner().newThread(runnable, ignoreParentCancellation, name);
    }

    @Override
    public boolean isIgnoreParentCancellation() {
        return task.cancellationScope.isIgnoreParentCancellation();
    }

    @Override
    public void cancel() {
        log.debug(String.format("Workflow thread \"%s\" cancel called", getName()));
        task.cancellationScope.cancel();
    }

    @Override
    public void cancel(String reason) {
        log.debug(String.format("Workflow thread \"%s cancel called with \"%s\" reason", getName(), reason));
        task.cancellationScope.cancel(reason);
    }

    @Override
    public String getCancellationReason() {
        return task.cancellationScope.getCancellationReason();
    }

    @Override
    public boolean isCancelRequested() {
        return task.cancellationScope.isCancelRequested();
    }

    @Override
    public Promise<String> getCancellationRequest() {
        return task.cancellationScope.getCancellationRequest();
    }

    @Override
    public boolean isDone(boolean skipChildren) {
        return task.cancellationScope.isDone(skipChildren);
    }

    @Override
    public boolean resetCanceled() {
        return task.cancellationScope.resetCanceled();
    }

    public void start() {
        if (context.getStatus() != Status.CREATED) {
            throw new IllegalThreadStateException("already started");
        }
        log.debug(String.format("Workflow thread \"%s\" started", getName()));
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
    public void join() {
        WorkflowThreadInternal.yield("WorkflowThread.join", this::isDone);
    }

    // TODO: Timeout support
    @Override
    public void join(long millis) {
        WorkflowThreadInternal.yield(millis, "WorkflowThread.join", this::isDone);
    }

    @Override
    public void join(Duration duration) {
        join(duration.toMillis());
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

    private void setBlockedUntil(long blockedUntil) {
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
        return context.runUntilBlocked();
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
        // Cannot call destroy() on itself
        if (thread == Thread.currentThread()) {
            context.exit();
        } else {
            context.destroy();
        }
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

    @Override
    public void addStackTrace(StringBuilder result) {
        result.append(getName());
        if (thread == null) {
            result.append("(NEW)");
            return;
        }
        result.append(": (BLOCKED on ").append(getContext().getYieldReason()).append(")\n");
        // These numbers might change if implementation changes.
        int omitTop = 5;
        int omitBottom = 7;
        if (DeterministicRunnerImpl.WORKFLOW_ROOT_THREAD_NAME.equals(getName())) {
            omitBottom = 11;
        }
        StackTraceElement[] stackTrace = thread.getStackTrace();
        for (int i = omitTop; i < stackTrace.length - omitBottom; i++) {
            StackTraceElement e = stackTrace[i];
            if (i == omitTop && "yield".equals(e.getMethodName())) continue;
            result.append(e);
            result.append("\n");
        }
    }

    /**
     * Stop executing all workflow threads and puts {@link DeterministicRunner} into closed state.
     * To be called only from a workflow thread.
     *
     * @param value accessible through {@link DeterministicRunner#getExitValue()}.
     */
    static <R> void exit(R value) {
        currentThreadInternal().runner.exit(value);
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
     * @throws CancellationException      if thread (or current cancellation scope was cancelled).
     * @throws DestroyWorkflowThreadError if thread was asked to be destroyed.
     */
    static void yield(String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        WorkflowThreadInternal.currentThreadInternal().getContext().yield(reason, unblockCondition);
    }

    /**
     * Block current thread until unblockCondition is evaluated to true or timeoutMillis passes.
     *
     * @return false if timed out.
     */
    static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
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

        YieldWithTimeoutCondition(Supplier<Boolean> unblockCondition, long blockedUntil) {
            this.unblockCondition = unblockCondition;
            this.blockedUntil = blockedUntil;
        }

        boolean isTimedOut() {
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
