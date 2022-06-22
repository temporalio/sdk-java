/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.sync;

import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.workflow.CancellationScope;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Executes code passed to {@link #newRunner} as well as threads created from it using {@link
 * WorkflowThread#newThread(Runnable, boolean)} deterministically. Requires use of provided wrappers
 * for synchronization and notification instead of native ones.
 */
interface DeterministicRunner {

  long DEFAULT_DEADLOCK_DETECTION_TIMEOUT_MS = 1000;

  /**
   * Create new instance of DeterministicRunner
   *
   * @param workflowContext workflow context to use
   * @param root function that root thread of the runner executes.
   * @param cache WorkflowExecutorCache used cache inflight workflows. New workflow threads will
   *     evict this cache when the thread pool runs out
   * @return instance of the DeterministicRunner.
   */
  static DeterministicRunner newRunner(
      WorkflowThreadExecutor workflowThreadExecutor,
      SyncWorkflowContext workflowContext,
      Runnable root,
      WorkflowExecutorCache cache) {
    return new DeterministicRunnerImpl(workflowThreadExecutor, workflowContext, root, cache);
  }

  /**
   * Create new instance of DeterministicRunner
   *
   * @param workflowThreadExecutor executor for workflow thread Runnables
   * @param workflowContext workflow context to use
   * @param root function that root thread of the runner executes.
   * @return instance of the DeterministicRunner.
   */
  static DeterministicRunner newRunner(
      WorkflowThreadExecutor workflowThreadExecutor,
      SyncWorkflowContext workflowContext,
      Runnable root) {
    return new DeterministicRunnerImpl(workflowThreadExecutor, workflowContext, root, null);
  }

  /**
   * ExecuteUntilAllBlocked executes threads one by one in deterministic order until all of them are
   * completed or blocked.
   *
   * @throws Throwable if one of the threads didn't handle an exception.
   * @param deadlockDetectionTimeout the maximum time in milliseconds a thread can run without
   *     calling yield.
   */
  void runUntilAllBlocked(long deadlockDetectionTimeout);

  /**
   * Request cancellation of the computation. Calls {@link CancellationScope#cancel(String)} on the
   * root scope that wraps the root Runnable.
   */
  void cancel(String reason);

  /** Destroys all controlled workflow threads, blocks until the threads are destroyed */
  void close();

  /** Stack trace of all threads owned by the DeterministicRunner instance */
  String stackTrace();

  /**
   * Executes a runnable in a specially created workflow thread. This newly created thread is given
   * chance to run before any other existing threads. This is used to ensure that some operations
   * (like signal callbacks) are executed before all other threads which is important to guarantee
   * their processing even if they were received after workflow code decided to complete. To be
   * called before runUntilAllBlocked.
   */
  void executeInWorkflowThread(String name, Runnable r);

  /**
   * Creates a new instance of a workflow child thread. To be called only from another workflow
   * thread.
   */
  @Nonnull
  WorkflowThread newWorkflowThread(Runnable runnable, boolean detached, @Nullable String name);

  /** Creates a new instance of a workflow callback thread. */
  @Nonnull
  WorkflowThread newCallbackThread(Runnable runnable, @Nullable String name);
}
