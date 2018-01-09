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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

class DeterministicRunnerImpl implements DeterministicRunner {

    private final Lock lock = new ReentrantLock();
    private final ExecutorService threadPool;
    private final SyncDecisionContext decisionContext;
    private List<WorkflowThreadImpl> threads = new LinkedList<>(); // protected by lock
    private List<WorkflowThreadImpl> threadsToAdd = Collections.synchronizedList(new ArrayList<>());
    private final Supplier<Long> clock;
    /**
     * Time at which any thread that runs under dispatcher can make progress.
     * For example when {@link WorkflowThread#sleep(long)} expires.
     * 0 means no blocked threads.
     */
    private long nextWakeUpTime;

    public DeterministicRunnerImpl(Runnable root) {
        this(new ThreadPoolExecutor(0, 1000, 1, TimeUnit.MINUTES, new SynchronousQueue<>()),
                null,
                System::currentTimeMillis, root);
    }

    public DeterministicRunnerImpl(ExecutorService threadPool, SyncDecisionContext decisionContext, Supplier<Long> clock, Runnable root) {
        this.threadPool = threadPool;
        this.decisionContext = decisionContext;
        this.clock = clock;
        // TODO: workflow instance specific thread name
        WorkflowThreadImpl rootWorkflowThreadImpl = new WorkflowThreadImpl(threadPool, this, "workflow-root", root);
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
            WorkflowThreadImpl callbackThread = null;
            if (decisionContext != null) {
                // Call callbacks from a thread owned by the dispatcher.
                callbackThread = newThread(decisionContext::fireTimers, "timer callbacks");
                callbackThread.start();
                threads.add(callbackThread);
            }
            Throwable unhandledException = null;
            // Keep repeating until at least one of the threads makes progress.
            boolean progress;
            do {
                threadsToAdd.clear();
                progress = false;
                ListIterator<WorkflowThreadImpl> ci = threads.listIterator();
                nextWakeUpTime = 0;
                while (ci.hasNext()) {
                    WorkflowThreadImpl c = ci.next();
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
            if (callbackThread != null && !callbackThread.isDone()) {
                throw new IllegalStateException("Callback thread blocked:\n" + callbackThread.getStackTrace());
            }
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
            return threads.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            for (WorkflowThreadImpl c : threads) {
                c.stop();
            }
            threads.clear();
        } finally {
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

    public WorkflowThreadImpl newThread(Runnable r) {
        return newThread(r, null);
    }

    public WorkflowThreadImpl newThread(Runnable r, String name) {
        WorkflowThreadImpl result = new WorkflowThreadImpl(threadPool, this, name, r);
        threadsToAdd.add(result); // This is synchronized collection.
        return result;
    }

    Lock getLock() {
        return lock;
    }
}
