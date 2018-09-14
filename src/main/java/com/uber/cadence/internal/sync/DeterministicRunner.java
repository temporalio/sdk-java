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

package com.uber.cadence.internal.sync;

import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.Workflow;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Executes code passed to {@link #newRunner(Runnable)} as well as threads created from it using
 * {@link WorkflowInternal#newThread(boolean, Runnable)} deterministically. Requires use of provided
 * wrappers for synchronization and notification instead of native ones.
 */
interface DeterministicRunner {

  static DeterministicRunner newRunner(Runnable root) {
    return new DeterministicRunnerImpl(root);
  }

  static DeterministicRunner newRunner(Supplier<Long> clock, Runnable root) {
    return new DeterministicRunnerImpl(clock, root);
  }

  /**
   * Create new instance of DeterministicRunner
   *
   * @param decisionContext decision context to use
   * @param clock Supplier that returns current time that sync should use
   * @param root function that root thread of the runner executes.
   * @param cache DeciderCache used cache inflight workflows. New workflow threads will evict this
   *     cache when the thread pool runs out
   * @return instance of the DeterministicRunner.
   */
  static DeterministicRunner newRunner(
      ExecutorService threadPool,
      SyncDecisionContext decisionContext,
      Supplier<Long> clock,
      Runnable root,
      DeciderCache cache) {
    return new DeterministicRunnerImpl(threadPool, decisionContext, clock, root, cache);
  }

  /**
   * Create new instance of DeterministicRunner
   *
   * @param decisionContext decision context to use
   * @param clock Supplier that returns current time that sync should use
   * @param root function that root thread of the runner executes.
   * @return instance of the DeterministicRunner.
   */
  static DeterministicRunner newRunner(
      ExecutorService threadPool,
      SyncDecisionContext decisionContext,
      Supplier<Long> clock,
      Runnable root) {
    return new DeterministicRunnerImpl(threadPool, decisionContext, clock, root, null);
  }

  /**
   * ExecuteUntilAllBlocked executes threads one by one in deterministic order until all of them are
   * completed or blocked.
   *
   * @throws Throwable if one of the threads didn't handle an exception.
   */
  void runUntilAllBlocked() throws Throwable;

  /** IsDone returns true when all of threads are completed */
  boolean isDone();

  /** @return exit value passed to {@link WorkflowThread#exit(Object)} */
  Object getExitValue();

  /**
   * Request cancellation of the computation. Calls {@link CancellationScope#cancel(String)} on the
   * root scope that wraps the root Runnable.
   */
  void cancel(String reason);

  /**
   * * Destroys all threads by throwing {@link DestroyWorkflowThreadError} without waiting for their
   * completion
   */
  void close();

  /** Stack trace of all threads owned by the DeterministicRunner instance */
  String stackTrace();

  /** @return time according to a clock configured with the Runner. */
  long currentTimeMillis();

  /**
   * @return time at which workflow can make progress. For example when {@link Workflow#sleep(long)}
   *     expires. 0 means that no time related blockages.
   */
  long getNextWakeUpTime();

  /**
   * Executes a runnable in a specially created workflow thread. This newly created thread is given
   * chance to run before any other existing threads. This is used to ensure that some operations
   * (like signal callbacks) are executed before all other threads which is important to guarantee
   * their processing even if they were received after workflow code decided to complete. To be
   * called before runUntilAllBlocked.
   */
  void executeInWorkflowThread(String name, Runnable r);
}
