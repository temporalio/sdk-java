package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class WorkflowThreadScheduler {
  // A single lock shared between all workflow threads of a workflow. Created in
  // DeterministicRunnerImpl
  private final Lock runnerLock;
  // Used to block runUntilBlocked call on
  private final Condition yieldedCondition;
  // Used to block yield call on
  private final Condition runCondition;

  private boolean yielded;
  private boolean completed;

  private int deadlockDetectionLockCounter;
  private long lastProgressTimestampNs;

  WorkflowThreadScheduler(Lock runnerLock) {
    this.runnerLock = runnerLock;
    this.runCondition = runnerLock.newCondition();
    this.yieldedCondition = runnerLock.newCondition();
  }

  public void madeProgressLocked() {
    this.lastProgressTimestampNs = System.nanoTime();
  }

  public void yieldLocked() throws InterruptedException {
    madeProgressLocked();
    this.yielded = true;
    this.yieldedCondition.signal();
    this.runCondition.await();
  }

  public void completeLocked() {
    madeProgressLocked();
    this.completed = true;
    this.yieldedCondition.signal();
  }

  public void scheduleLocked() {
    Preconditions.checkState(!this.completed, "shouldn't schedule completed workflow thread");
    this.yielded = false;
    this.runCondition.signal();
  }

  public void lockDeadlockDetection() {
    this.runnerLock.lock();
    try {
      ++this.deadlockDetectionLockCounter;
      // no need to update lastProgressTimestampNs here as we don't rely on it if
      // deadlockDetectionLockCounter > 0
    } finally {
      this.runnerLock.unlock();
    }
  }

  public void unlockDeadlockDetection() {
    this.runnerLock.lock();
    try {
      int newValue = --deadlockDetectionLockCounter;
      Preconditions.checkState(
          newValue >= 0, "Unbalanced lockDeadlockDetection/unlockDeadlockDetection calls");
      if (newValue == 0) {
        // no need to update lastProgressTimestampNs here if != 0 as we don't rely on it if
        // deadlockDetectionLockCounter > 0
        this.lastProgressTimestampNs = System.nanoTime();
      }
    } finally {
      this.runnerLock.unlock();
    }
  }

  public WaitForYieldResult waitForYieldLocked(long deadlockDetectionTimeout, TimeUnit unit)
      throws InterruptedException {
    long deadlockDetectionTimeoutNs = unit.toNanos(deadlockDetectionTimeout);
    Preconditions.checkState(
        deadlockDetectionLockCounter == 0,
        "Unbalanced lockDeadlockDetection/unlockDeadlockDetection calls");
    this.lastProgressTimestampNs = System.nanoTime();
    long sinceLastProgressMadeNs = 0;
    while (true) {
      boolean timedOut =
          !this.yieldedCondition.await(
              deadlockDetectionTimeoutNs - sinceLastProgressMadeNs, TimeUnit.NANOSECONDS);

      // 1. It is possible that the await() is expired, but it happened when the controlled thread
      // had already reached the yielding/completion point and was under the lock. If it's the
      // case, don't detect a deadlock.
      // 2. It's also a normal exit condition
      if (this.yielded || this.completed) {
        Preconditions.checkState(
            this.deadlockDetectionLockCounter == 0,
            "Unbalanced lockDeadlockDetection/unlockDeadlockDetection calls");
        return completed ? WaitForYieldResult.COMPLETED : WaitForYieldResult.YIELDED;
      }

      if (timedOut) {
        // if under a detection lock, we set the elapsed time to 0
        sinceLastProgressMadeNs =
            this.deadlockDetectionLockCounter == 0
                ? System.nanoTime() - this.lastProgressTimestampNs
                : 0;
        if (sinceLastProgressMadeNs >= deadlockDetectionTimeoutNs) {
          return WaitForYieldResult.DEADLOCK_DETECTED;
        }
      }
    }
  }

  enum WaitForYieldResult {
    YIELDED,
    COMPLETED,
    DEADLOCK_DETECTED
  }
}
