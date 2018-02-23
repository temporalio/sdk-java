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

import com.uber.cadence.internal.worker.CheckedExceptionWrapper;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

class DeterministicRunnerImpl implements DeterministicRunner {

    private static final Log log = LogFactory.getLog(DeterministicRunnerImpl.class);
    public static final String WORKFLOW_ROOT_THREAD_NAME = "workflow-root";
    private static final ThreadLocal<DeterministicRunnerCoroutine> currentThreadThreadLocal = new ThreadLocal<>();

    private final Lock lock = new ReentrantLock();
    private final ExecutorService threadPool;
    private final SyncDecisionContext decisionContext;
    private LinkedList<DeterministicRunnerCoroutine> threads = new LinkedList<>(); // protected by lock
    private List<DeterministicRunnerCoroutine> threadsToAdd = Collections.synchronizedList(new ArrayList<>());
    private final Supplier<Long> clock;
    private boolean closed;

    static DeterministicRunnerCoroutine currentThreadInternal() {
        DeterministicRunnerCoroutine result = currentThreadThreadLocal.get();
        if (result == null) {
            throw new IllegalStateException("Called from non workflow or workflow callback thread");
        }
        return result;
    }

    static void setCurrentThreadInternal(DeterministicRunnerCoroutine coroutine) {
        currentThreadThreadLocal.set(coroutine);
    }

    /**
     * Time at which any thread that runs under dispatcher can make progress.
     * For example when {@link com.uber.cadence.workflow.Workflow#sleep(long)} expires.
     * 0 means no blocked threads.
     */
    private long nextWakeUpTime;
    /**
     * Used to check for failedPromises that contain an error, but never where accessed.
     * It is to avoid failure swallowing by failedPromises which is very hard to troubleshoot.
     */
    private Set<Promise> failedPromises = new HashSet<>();
    private Object exitValue;
    private WorkflowThreadInternal rootWorkflowThread;
    private final CancellationScopeImpl runnerCancellationScope;


    DeterministicRunnerImpl(Runnable root) {
        this(System::currentTimeMillis, root);
    }

    DeterministicRunnerImpl(Supplier<Long> clock, Runnable root) {
        this(new ThreadPoolExecutor(0, 1000, 1, TimeUnit.MINUTES, new SynchronousQueue<>()),
                null,
                clock, root);
    }

    DeterministicRunnerImpl(ExecutorService threadPool, SyncDecisionContext decisionContext, Supplier<Long> clock, Runnable root) {
        this.threadPool = threadPool;
        this.decisionContext = decisionContext;
        this.clock = clock;
        runnerCancellationScope = new CancellationScopeImpl(true, null, null);
        // TODO: workflow instance specific thread name
        rootWorkflowThread = new WorkflowThreadInternal(true, threadPool, this,
                WORKFLOW_ROOT_THREAD_NAME, false, runnerCancellationScope, root);
        threads.add(rootWorkflowThread);
        rootWorkflowThread.start();
    }

    public SyncDecisionContext getDecisionContext() {
        return decisionContext;
    }

    @Override
    public void runUntilAllBlocked() throws Throwable {
        lock.lock();
        try {
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("closed");
                }
            } finally {
                lock.unlock();
            }
            Throwable unhandledException = null;
            // Keep repeating until at least one of the threads makes progress.
            boolean progress;
            do {
                threadsToAdd.clear();
                progress = false;
                ListIterator<DeterministicRunnerCoroutine> ci = threads.listIterator();
                nextWakeUpTime = 0;
                while (ci.hasNext()) {
                    DeterministicRunnerCoroutine c = ci.next();
                    progress = c.runUntilBlocked() || progress;
                    if (c.isDone()) {
                        ci.remove();
                        if (c.getUnhandledException() != null) {
                            unhandledException = c.getUnhandledException();
                            break;
                        }
                    } else {
                        long t = c.getBlockedUntil();
                        if (t > nextWakeUpTime) {
                            nextWakeUpTime = t;
                        }
                    }
                }
                if (unhandledException != null) {
                    close();
                    throw unhandledException;
                }
                threads.addAll(threadsToAdd);
            } while (progress && !threads.isEmpty());
            if (nextWakeUpTime < currentTimeMillis()) {
                nextWakeUpTime = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isDone() {
        lock.lock();
        try {
            return closed || threads.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <R> R getExitValue() {
        return (R) exitValue;
    }

    @Override
    public void cancel(String reason) {
        rootWorkflowThread.cancel(reason);
    }

    @Override
    public void close() {
        lock.lock();
        if (closed) {
            return;
        }
        try {
            for (DeterministicRunnerCoroutine c : threads) {
                c.stop();
            }
            threads.clear();
            for (Promise<?> f : failedPromises) {
                if (!f.isCompleted()) {
                    throw new Error("expected failed");
                }
                try {
                    f.get();
                    throw new Error("unreachable");
                } catch (RuntimeException e) {
                    log.warn("Promise that was completedExceptionally was never accessed. " +
                            "The ignored exception:", CheckedExceptionWrapper.unwrap(e));
                }
            }
        } finally {
            closed = true;
            lock.unlock();
        }
    }

    @Override
    public String stackTrace() {
        StringBuilder result = new StringBuilder();
        lock.lock();
        try {
            for (DeterministicRunnerCoroutine coroutine : threads) {
                if (coroutine instanceof CallbackCoroutine) {
                    continue;
                }
                if (result.length() > 0) {
                    result.append("\n");
                }
                coroutine.addStackTrace(result);
            }
        } finally {
            lock.unlock();
        }
        return result.toString();
    }

    @Override
    public long currentTimeMillis() {
        return clock.get();
    }

    @Override
    public long getNextWakeUpTime() {
        if (decisionContext != null) {
            long nextFireTime = decisionContext.getNextFireTime();
            if (nextWakeUpTime == 0) {
                return nextFireTime;
            }
            if (nextFireTime == 0) {
                return nextWakeUpTime;
            }
            return Math.min(nextWakeUpTime, nextFireTime);
        }
        return nextWakeUpTime;
    }

    public WorkflowThreadInternal newThread(Runnable runnable, boolean ignoreParentCancellation, String name) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        } finally {
            lock.unlock();
        }
        WorkflowThreadInternal result = new WorkflowThreadInternal(false, threadPool, this, name,
                ignoreParentCancellation, CancellationScopeImpl.current(), runnable);
        threadsToAdd.add(result); // This is synchronized collection.
        return result;
    }

    @Override
    public void newCallbackTask(Functions.Func<Boolean> task, String taskName) {
        lock.lock();
        if (closed) {
            throw new IllegalStateException("closed");
        }
        try {

            threads.add(new CallbackCoroutine(threadPool, this, taskName, task, false,
                    WorkflowInternal.currentCancellationScope()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WorkflowThread newBeforeThread(String name, Runnable r) {
        WorkflowThreadInternal result = new WorkflowThreadInternal(false, threadPool, this, name,
                false, runnerCancellationScope, r);
        result.start();
        lock.lock();
        try {
            threads.addFirst(result);
        } finally {
            lock.unlock();
        }
        return result;
    }

    Lock getLock() {
        return lock;
    }

    /**
     * Register a promise that had failed but wasn't accessed yet.
     */
    public void registerFailedPromise(Promise promise) {
        failedPromises.add(promise);
    }

    /**
     * Forget a failed promise as it was accessed.
     */
    public void forgetFailedPromise(Promise promise) {
        failedPromises.remove(promise);
    }

    <R> void exit(R value) {
        this.exitValue = value;
        close();
    }
}
