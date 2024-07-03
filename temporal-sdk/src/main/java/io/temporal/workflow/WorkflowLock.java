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
