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

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Executes code passed to {@link #newRunner(Functions.Proc)}
 * as well as threads created from it using {@link WorkflowInternal#newThread(Functions.Proc)} deterministically.
 * Requires use of provided wrappers for synchronization and notification instead of native ones.
 */
interface DeterministicRunner {

    static DeterministicRunner newRunner(Functions.Proc root) {
        return new DeterministicRunnerImpl(root);
    }

    static DeterministicRunner newRunner(Supplier<Long> clock, Functions.Proc root) {
        return new DeterministicRunnerImpl(clock, root);
    }

    /**
     * Create new instance of DeterministicRunner
     *
     * @param decisionContext decision context to use
     * @param clock           Supplier that returns current time that dispatcher should use
     * @param root            function that root thread of the runner executes.
     * @return instance of the DeterministicRunner.
     */
    static DeterministicRunner newRunner(ExecutorService threadPool, SyncDecisionContext decisionContext, Supplier<Long> clock, Functions.Proc root) {
        return new DeterministicRunnerImpl(threadPool, decisionContext, clock, root);
    }

    /**
     * ExecuteUntilAllBlocked executes threads one by one in deterministic order
     * until all of them are completed or blocked.
     *
     * @return nearest time when at least one of the threads is expected to wake up.
     * @throws Throwable if one of the threads didn't handle an exception.
     */
    void runUntilAllBlocked() throws Throwable;

    /**
     * IsDone returns true when all of threads are completed
     */
    boolean isDone();

    /**
     * @return exit value passed to WorkflowThreadInternal.exit().
     */
    <R> R getExitValue();

    /**
     * * Destroys all threads by throwing {@link DestroyWorkflowThreadError} without waiting for their completion
     */
    void close();

    /**
     * Stack trace of all threads owned by the DeterministicRunner instance
     */
    String stackTrace();

    /**
     * @return time according to a clock configured with the Runner.
     */
    long currentTimeMillis();

    /**
     * @return time at which workflow can make progress.
     * For example when {@link WorkflowThread#sleep(long)} expires.
     * 0 means that no time related blockages.
     */
    long getNextWakeUpTime();

    /**
     * Add new task that is executed every time runUntilAllBlocked is called. The task should not
     * block thread using yield. To be used to execute various callback functions.
     * If task returns true it made some progress (for example executed some callbacks) when called.
     */
    void newCallbackTask(Functions.Func<Boolean> task, String taskName);

    /**
     * Adds already started thread before all other threads. To be called before runUntilAllBlocked.
     * This is used to ensure that some operations (like signal callbacks) are executed before other threads.
     */
    WorkflowThread newBeforeThread(Functions.Proc r, String name);
}