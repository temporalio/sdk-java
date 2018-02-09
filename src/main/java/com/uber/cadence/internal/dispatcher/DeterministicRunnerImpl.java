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
import com.uber.cadence.workflow.WorkflowFuture;
import com.uber.cadence.workflow.WorkflowThread;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

class DeterministicRunnerImpl implements DeterministicRunner {

    private static final Log log = LogFactory.getLog(DeterministicRunnerImpl.class);

    private final Lock lock = new ReentrantLock();
    private final ExecutorService threadPool;
    private final SyncDecisionContext decisionContext;
    private LinkedList<DeterministicRunnerCoroutine> threads = new LinkedList<>(); // protected by lock
    private List<DeterministicRunnerCoroutine> threadsToAdd = Collections.synchronizedList(new ArrayList<>());
    private final Supplier<Long> clock;
    private boolean closed;

    /**
     * Time at which any thread that runs under dispatcher can make progress.
     * For example when {@link WorkflowThread#sleep(long)} expires.
     * 0 means no blocked threads.
     */
    private long nextWakeUpTime;
    /**
     * Used to check for failedFutures that contain an error, but never where accessed.
     * It is to avoid failure swallowing by failedFutures which is very hard to troubleshoot.
     */
    private Set<WorkflowFuture> failedFutures = new HashSet<>();
    private Object exitValue;

    public DeterministicRunnerImpl(Functions.Proc root) {
        this(System::currentTimeMillis, root);
    }

    public DeterministicRunnerImpl(Supplier<Long> clock, Functions.Proc root) {
        this(new ThreadPoolExecutor(0, 1000, 1, TimeUnit.MINUTES, new SynchronousQueue<>()),
                null,
                clock, root);
    }

    public DeterministicRunnerImpl(ExecutorService threadPool, SyncDecisionContext decisionContext, Supplier<Long> clock, Functions.Proc root) {
        this.threadPool = threadPool;
        this.decisionContext = decisionContext;
        this.clock = clock;
        // TODO: workflow instance specific thread name
        WorkflowThreadInternal rootWorkflowThreadImpl = new WorkflowThreadInternal(threadPool, this, "workflow-root", root);
        threads.add(rootWorkflowThreadImpl);
        rootWorkflowThreadImpl.start();
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
            for (WorkflowFuture<?> f : failedFutures) {
                try {
                    f.get();
                    throw new Error("unreachable");
                } catch (ExecutionException e) {
                    log.warn("Failed WorkflowFuture was never accessed. The ignored exception:", e.getCause());
                } catch (InterruptedException e) {
                }
            }
        } finally {
            closed = true;
            lock.unlock();
        }
    }

    @Override
    public String stackTrace() {
        return null;
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

    public WorkflowThreadInternal newThread(Functions.Proc r) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        } finally {
            lock.unlock();
        }
        return newThread(r, null);
    }

    public WorkflowThreadInternal newThread(Functions.Proc r, String name) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        } finally {
            lock.unlock();
        }
        WorkflowThreadInternal result = new WorkflowThreadInternal(threadPool, this, name, r);
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
            threads.add(new CallbackCoroutine(threadPool, this, taskName, task));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WorkflowThread newBeforeThread(Functions.Proc r, String name) {
        WorkflowThreadInternal result = new WorkflowThreadInternal(threadPool, this, name, r);
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
     * Register a future that had failed but wasn't accessed yet.
     */
    public <T> void registerFailedFuture(WorkflowFuture future) {
        failedFutures.add(future);
    }

    /**
     * Forget a failed future as it was accessed.
     */
    public <T> void forgetFailedFuture(WorkflowFuture future) {
        failedFutures.remove(future);
    }

    <R> void exit(R value) {
        this.exitValue = value;
        close();
    }
}
