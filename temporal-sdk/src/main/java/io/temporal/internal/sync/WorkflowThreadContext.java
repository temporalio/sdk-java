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

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.temporal.internal.common.DebugModeUtils;
import io.temporal.workflow.Functions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WorkflowThreadContext {
  private static final Logger log = LoggerFactory.getLogger(WorkflowThreadContext.class);

  // Shared runner lock
  private final Lock lock;
  // Used to block await call
  private final Condition yieldCondition;
  // Used to block runUntilBlocked call
  private final Condition runCondition;
  // Used to block evaluateInCoroutineContext
  private final Condition evaluationCondition;

  // thread safety of these field is guaranteed by taking a #lock for reading or updating
  private Status status = Status.CREATED;
  @Nullable private Thread currentThread;

  private Functions.Proc1<String> evaluationFunction;
  private Throwable unhandledException;
  private boolean inRunUntilBlocked;
  private boolean remainedBlocked;
  private String yieldReason;
  private boolean destroyRequested;

  WorkflowThreadContext(Lock lock) {
    this.lock = lock;
    this.yieldCondition = lock.newCondition();
    this.runCondition = lock.newCondition();
    this.evaluationCondition = lock.newCondition();
  }

  /**
   * Initial yield allows the workflow thread to block at the start before exercising any user code.
   * This gives a control over when the execution may be started. It also provides happens-before
   * relationship with other threads and DeterministicRunnerImpl through shared lock.
   */
  public void initialYield() {
    Status status = getStatus();
    if (status == Status.DONE) {
      throw new DestroyWorkflowThreadError("done in initialYield");
    }
    Preconditions.checkState(status == Status.RUNNING, "threadContext not in RUNNING state");
    this.yield("created", () -> true);
  }

  public void yield(String reason, Supplier<Boolean> unblockFunction) {
    if (unblockFunction == null) {
      throw new IllegalArgumentException("null unblockFunction");
    }
    // Evaluates unblockFunction out of the lock to avoid deadlocks.
    lock.lock();
    try {
      if (destroyRequested) {
        throw new DestroyWorkflowThreadError();
      }
      yieldReason = reason;

      while (!inRunUntilBlocked || !unblockFunction.get()) {
        status = Status.YIELDED;
        runCondition.signal();
        yieldCondition.await();
        if (destroyRequested) {
          throw new DestroyWorkflowThreadError();
        }
        maybeEvaluateLocked(reason);
      }

      setStatus(Status.RUNNING);
      yieldReason = null;
    } catch (InterruptedException e) {
      // Throwing Error in workflow code aborts workflow task without failing workflow.
      Thread.currentThread().interrupt();
      if (!isDestroyRequested()) {
        throw new Error("Unexpected interrupt", e);
      }
    } finally {
      remainedBlocked = false;
      lock.unlock();
    }
  }

  /**
   * Execute evaluation function by the thread that owns this context if {@link
   * #evaluateInCoroutineContext(Functions.Proc1)} was called.
   *
   * <p>Should be called under the lock
   *
   * @param reason human-readable reason for current thread blockage passed to await call.
   */
  private void maybeEvaluateLocked(String reason) {
    if (status == Status.EVALUATING) {
      try {
        evaluationFunction.apply(reason);
      } catch (Exception e) {
        evaluationFunction.apply(Throwables.getStackTraceAsString(e));
      } finally {
        status = Status.RUNNING;
        evaluationCondition.signal();
      }
    }
  }

  /**
   * Call {@code function} by the thread that owns this context and is currently blocked in a await.
   * Used to get information about current state of the thread like current stack trace.
   *
   * @param function to evaluate. Consumes reason for yielding as a parameter.
   */
  public void evaluateInCoroutineContext(Functions.Proc1<String> function) {
    lock.lock();
    try {
      Preconditions.checkArgument(function != null, "null function");
      if (status != Status.YIELDED && status != Status.RUNNING) {
        throw new IllegalStateException("Not in yielded status: " + status);
      }
      if (evaluationFunction != null) {
        // We need to make sure no parallel evaluateInCoroutineContext are permitted.
        // The lock itself is not enough, because we call `await` later in the method body that
        // releases the original lock.
        // So we do a non-atomic CAS by comparing `evaluationFunction` with null and setting it on
        // entrance and resetting it on exit under a lock to achieve exclusivity.
        // We don't need an atomic CAS here, because we are under the lock when we perform these
        // operations.
        // A more explicit solution may be implemented using a separate lock for evaluate calls.
        throw new IllegalStateException("Already evaluating");
      }
      Preconditions.checkState(!inRunUntilBlocked, "Running runUntilBlocked");
      evaluationFunction = function;
      status = Status.EVALUATING;
      yieldCondition.signal();
      while (status == Status.EVALUATING) {
        evaluationCondition.await();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Error("Unexpected interrupt", e);
    } finally {
      evaluationFunction = null;
      lock.unlock();
    }
  }

  public Status getStatus() {
    lock.lock();
    try {
      return status;
    } finally {
      lock.unlock();
    }
  }

  public void setStatus(Status status) {
    // Unblock runUntilBlocked if thread exited instead of yielding.
    lock.lock();
    try {
      this.status = status;
      if (isDone()) {
        runCondition.signal();
        // it's important to clear the thread after or together (under one lock) when setting the
        // status, so nobody sees the context yet with RUNNING status, but without a currentThread
        clearCurrentThreadLocked();
      }
    } finally {
      lock.unlock();
    }
  }

  public boolean isDone() {
    lock.lock();
    try {
      return status == Status.DONE;
    } finally {
      lock.unlock();
    }
  }

  public Throwable getUnhandledException() {
    lock.lock();
    try {
      return unhandledException;
    } finally {
      lock.unlock();
    }
  }

  public void setUnhandledException(Throwable unhandledException) {
    lock.lock();
    try {
      this.unhandledException = unhandledException;
    } finally {
      lock.unlock();
    }
  }

  public String getYieldReason() {
    return yieldReason;
  }

  /**
   * @param deadlockDetectionTimeoutMs maximum time in milliseconds the thread can run before
   *     calling yield. Discarded if {@code TEMPORAL_DEBUG} env variable is set.
   * @return true if thread made some progress. Which is await was unblocked and some code after it
   *     * was executed.
   */
  public boolean runUntilBlocked(long deadlockDetectionTimeoutMs) {
    if (DebugModeUtils.isTemporalDebugModeOn()) {
      deadlockDetectionTimeoutMs = Long.MAX_VALUE;
    }
    lock.lock();
    try {
      if (status == Status.DONE) {
        return false;
      }
      Preconditions.checkState(
          evaluationFunction == null, "Cannot runUntilBlocked while evaluating");
      inRunUntilBlocked = true;
      if (status == Status.YIELDED) {
        // we have to swap it here to allow potentialProgressStatesLocked to start returning true
        status = Status.RUNNING;
      }
      remainedBlocked = true;
      yieldCondition.signal();
      while (potentialProgressStatesLocked()) {
        boolean awaitTimedOut =
            !runCondition.await(deadlockDetectionTimeoutMs, TimeUnit.MILLISECONDS);
        if (awaitTimedOut
            // check that the condition is still true after acquiring the lock back
            // (it could be moved into DONE meanwhile)
            && potentialProgressStatesLocked()) {
          long detectionTimestamp = System.currentTimeMillis();
          if (currentThread != null) {
            throw new PotentialDeadlockException(currentThread.getName(), this, detectionTimestamp);
          } else {
            // This should never happen.
            // We clear currentThread only after setting the status to DONE.
            // And we check for it by the status condition check after waking up on the condition
            // and acquiring the lock back
            log.warn(
                "Illegal State: WorkflowThreadContext has no currentThread in {} state", status);
            throw new PotentialDeadlockException("UnknownThread", this, detectionTimestamp);
          }
        }
        Preconditions.checkState(
            evaluationFunction == null, "Cannot runUntilBlocked while evaluating");
      }
      return !remainedBlocked;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!isDestroyRequested()) {
        throw new Error("Unexpected interrupt", e);
      }
      return true;
    } finally {
      inRunUntilBlocked = false;
      lock.unlock();
    }
  }

  /**
   * Should be called under the lock.
   *
   * @return true is current status is RUNNING or CREATED - two states we actively monitor for
   *     potential deadlocks
   */
  private boolean potentialProgressStatesLocked() {
    return status == Status.RUNNING || status == Status.CREATED;
  }

  public boolean isDestroyRequested() {
    lock.lock();
    try {
      return destroyRequested;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Non-blocking call, never throws.<br>
   * There is no guarantee that the thread is destroyed at the end of this call.
   */
  void initiateDestroy() {
    lock.lock();
    try {
      destroyRequested = true;
      if (status == Status.CREATED) {
        // prevent from running
        status = Status.DONE;
        return;
      }
      if (status == Status.RUNNING) {
        // we don't want to trigger an event loop if we are running already
        return;
      }
      if (status == Status.DONE) {
        // nothing to destroy
        return;
      }
      yieldCondition.signal();
    } finally {
      lock.unlock();
    }
  }

  public void initializeCurrentThread(Thread currentThread) {
    lock.lock();
    try {
      this.currentThread = currentThread;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Call at the end of the execution to set up a current thread to null because it could be running
   * a different workflow already and doesn't belong to this context anymore.
   *
   * <p>Should be called under the lock
   */
  private void clearCurrentThreadLocked() {
    this.currentThread = null;
  }

  /**
   * @return current thread that owns this context, could be null if the execution finished or
   *     didn't start yet
   */
  @Nullable
  public Thread getCurrentThread() {
    lock.lock();
    try {
      return currentThread;
    } finally {
      lock.unlock();
    }
  }
}
