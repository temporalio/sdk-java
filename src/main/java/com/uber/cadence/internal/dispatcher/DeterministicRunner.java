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

/**
 * Executes code passed to {@link #newRunner(Runnable)} as well as threads created from it using
 * {@link Workflow#newThread(Runnable)} deterministically. Requires use of provided wrappers for synchronization
 * and notification instead of native ones.
 */
public interface DeterministicRunner {

    static DeterministicRunner newRunner(Runnable root) {
        return new DeterministicRunnerImpl(root);
    }

    static DeterministicRunner newRunner(SyncDecisionContext decisionContext, WorkflowClock clock, Runnable root) {
        return new DeterministicRunnerImpl(decisionContext, clock, root);
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
     * * Destroys all threads by throwing {@link DestroyWorkflowThreadError} without waiting for their completion
     */
    void close();

    /**
     * Stack trace of all threads owned by the DeterministicRunner instance
     */
    String stackTrace();

    /**
     * @return time according to a {@link WorkflowClock} configured with the Runner.
     */
    long currentTimeMillis();

    /**
     * @return time at which workflow can make progress.
     * For example when {@link WorkflowThread#sleep(long)} expires.
     * 0 means that no time related blockages.
     */
    long getNextWakeUpTime();
}
