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

package io.temporal.workflow;

import java.time.Duration;

/**
 * Workflow lock is an alternative to {@link java.util.concurrent.locks.Lock} that is deterministic
 * and compatible with Temporal's concurrency model. API is designed to be used in a workflow code
 * only. It is not allowed to be used in an activity code.
 *
 * <p>In Temporal concurrency model, only one thread in a workflow code can execute at a time.
 */
public interface WorkflowLock {
  /**
   * Acquires the lock.
   *
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   */
  void lock();

  /**
   * Acquires the lock only if it is free at the time of invocation.
   *
   * @return true if the lock was acquired and false otherwise
   */
  boolean tryLock();

  /**
   * Acquires the lock if it is free within the given waiting time.
   *
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   * @return true if the lock was acquired and false if the waiting time elapsed before the lock was
   *     acquired.
   */
  boolean tryLock(Duration timeout);

  /** Releases the lock. */
  void unlock();
}
