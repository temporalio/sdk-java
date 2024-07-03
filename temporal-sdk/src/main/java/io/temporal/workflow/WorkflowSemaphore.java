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
 * Workflow semaphore is an alternative to {@link java.util.concurrent.Semaphore} that is
 * deterministic and compatible with Temporal's concurrency model. API is designed to be used in a
 * workflow code only. It is not allowed to be used in an activity code.
 *
 * <p>In Temporal concurrency model, only one thread in a workflow code can execute at a time.
 */
public interface WorkflowSemaphore {
  /**
   * Acquires a permit from this semaphore, blocking until one is available.
   *
   * <p>Acquires a permit, if one is available and returns immediately, reducing the number of
   * available permits by one.
   *
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   */
  void acquire();

  /**
   * Acquires the given number of permits from this semaphore, blocking until all are available.
   *
   * <p>Acquires the given number of permits, if they are available, and returns immediately,
   * reducing the number of available permits by the given amount.
   *
   * @param permits the number of permits to acquire
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   * @throws IllegalArgumentException if permits is negative
   */
  void acquire(int permits);

  /**
   * Acquires the given number of permits from this semaphore, only if all are available at the time
   * of invocation.
   *
   * <p>Acquires a permit, if one is available and returns immediately, with the value true,
   * reducing the number of available permits by one.
   *
   * @return true if the permit was acquired and false otherwise
   */
  boolean tryAcquire();

  /**
   * Acquires a permit from this semaphore, if one becomes available within the given waiting time.
   *
   * <p>Acquires a permit, if one is available and returns immediately, with the value true,
   * reducing the number of available permits by one.
   *
   * @param timeout the maximum time to wait for a permit
   * @return true if a permit was acquired and false if the waiting time elapsed before a permit was
   *     acquired
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   */
  boolean tryAcquire(Duration timeout);

  /**
   * Acquires the given number of permits from this semaphore, only if all are available at the time
   * of invocation.
   *
   * <p>Acquires the given number of permits, if they are available, and returns immediately, with
   * the value true, reducing the number of available permits by the given amount.
   *
   * <p>If insufficient permits are available then this method will return immediately with the
   * value false and the number of available permits is unchanged.
   *
   * @param permits the number of permits to acquire
   * @return true if the permits were acquired and false otherwise
   * @throws IllegalArgumentException if permits is negative
   */
  boolean tryAcquire(int permits);

  /**
   * Acquires the given number of permits from this semaphore, if all become available within the
   * given waiting time.
   *
   * <p>Acquires the given number of permits, if they are available and returns immediately, with
   * the value true, reducing the number of available permits by the given amount.
   *
   * @param permits the number of permits to acquire
   * @param timeout the maximum duration to wait for a permit
   * @return true if the permits was acquired and false if the waiting time elapsed before a permit
   *     was acquired
   * @throws io.temporal.failure.CanceledFailure if thread (or current {@link CancellationScope} was
   *     canceled).
   * @throws IllegalArgumentException if permits is negative
   */
  boolean tryAcquire(int permits, Duration timeout);

  /**
   * Releases a permit, returning it to the semaphore.
   *
   * <p>There is no requirement that a coroutine that releases a permit must have acquired that
   * permit by calling {@link #acquire()}. Correct usage of a semaphore is established by
   * programming convention in the application.
   */
  void release();

  /**
   * Releases the given number of permits, returning them to the semaphore.
   *
   * <p>There is no requirement that a coroutine that releases a permit must have acquired that
   * permit by calling {@link #acquire()}. Correct usage of a semaphore is established by
   * programming convention in the application.
   *
   * @param permits the number of permits to release
   * @throws IllegalArgumentException if permits is negative
   */
  void release(int permits);
}
