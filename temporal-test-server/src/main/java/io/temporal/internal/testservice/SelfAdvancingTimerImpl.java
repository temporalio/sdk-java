/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.testservice;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Uninterruptibles;
import io.temporal.workflow.Functions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SelfAdvancingTimerImpl implements SelfAdvancingTimer {

  private static class TimerTask {

    private final long executionTime;
    private final Runnable runnable;
    private final String taskInfo;
    private boolean canceled;

    TimerTask(long executionTime, Runnable runnable, String taskInfo) {
      this.executionTime = executionTime;
      this.runnable = runnable;
      this.taskInfo = taskInfo;
    }

    long getExecutionTime() {
      return executionTime;
    }

    public Runnable getRunnable() {
      return runnable;
    }

    String getTaskInfo() {
      return taskInfo;
    }

    @Override
    public String toString() {
      return "TimerTask{" + "executionTime=" + executionTime + '}';
    }

    public boolean isCanceled() {
      return canceled;
    }

    public void cancel() {
      canceled = true;
    }
  }

  private class TimerPump implements Runnable {

    @Override
    public void run() {
      lock.lock();
      try {
        runLocked();
      } catch (RuntimeException e) {
        log.error("Timer pump failed", e);
      } finally {
        lock.unlock();
      }
    }

    private void runLocked() {
      while (!Thread.currentThread().isInterrupted()) {
        updateTimeLocked();
        if (!emptyQueue && tasks.isEmpty()) {
          if (timeLockOnEmptyQueueHandle != null) {
            throw new IllegalStateException(
                "SelfAdvancingTimerImpl should have no taken time lock when queue is not empty, but handle is not null");
          }
          // Switching to wall time when no tasks scheduled
          timeLockOnEmptyQueueHandle =
              lockTimeSkippingLocked("SelfAdvancingTimerImpl runLocked empty-queue");
          emptyQueue = true;
        }
        TimerTask peekedTask = tasks.peek();
        if (peekedTask != null) {
          log.trace(
              "peekedTask="
                  + peekedTask.getTaskInfo()
                  + ", executionTime="
                  + peekedTask.getExecutionTime()
                  + ", canceled="
                  + peekedTask.isCanceled());
        }
        if (peekedTask != null
            && (peekedTask.getExecutionTime() <= currentTimeMs || peekedTask.isCanceled())) {
          try {
            LockHandle lockHandle = lockTimeSkippingLocked("runnable " + peekedTask.getTaskInfo());
            TimerTask polledTask = tasks.poll();
            log.trace(
                "running task="
                    + peekedTask.getTaskInfo()
                    + ", executionTime="
                    + peekedTask.getExecutionTime());

            if (polledTask.isCanceled()) {
              log.trace("Removed canceled task from the task queue: {}", polledTask.getTaskInfo());
              lockHandle.unlock();
            } else {
              Runnable runnable = polledTask.getRunnable();
              executor.execute(
                  () -> {
                    try {
                      runnable.run();
                    } catch (Throwable e) {
                      log.error("Unexpected failure in timer callback", e);
                    } finally {
                      lockHandle.unlock();
                    }
                  });
            }
          } catch (RuntimeException e) {
            log.error("Timer task failure", e);
          }
          continue;
        }

        final long timeToAwait;
        if (peekedTask != null) {
          timeToAwait = peekedTask.getExecutionTime() - currentTimeMs;
          if (log.isTraceEnabled()) {
            log.trace("Waiting for {}", Duration.ofMillis(timeToAwait));
            for (TimerTask task : tasks) {
              log.trace(
                  "Outstanding task: +{} {} {}",
                  Duration.ofMillis(task.executionTime - currentTimeMs),
                  task.taskInfo,
                  task.canceled);
            }
          }
        } else {
          timeToAwait = Long.MAX_VALUE;
        }

        try {
          condition.await(timeToAwait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }

  private class LockEvent {
    final String caller;
    final LockEventType lockType;
    final long timestamp;

    public LockEvent(String caller, LockEventType lockType) {
      this.caller = caller;
      this.lockType = lockType;
      this.timestamp = systemClock.millis();
    }
  }

  private enum LockEventType {
    LOCK,
    UNLOCK;

    @Override
    public String toString() {
      return this == LOCK ? "L" : "U";
    }
  }

  private class TimerLockHandle implements LockHandle {
    private final LockEvent event;

    public TimerLockHandle(LockEvent event) {
      this.event = event;
    }

    @Override
    public void unlock() {
      unlock(null);
    }

    @Override
    public void unlock(String caller) {
      lock.lock();
      try {
        unlockFromHandleLocked();
        condition.signal();
      } finally {
        lock.unlock();
      }
    }

    private void unlockFromHandleLocked() {
      boolean removed = lockEvents.remove(event);
      if (!removed) {
        throw new IllegalStateException("Unbalanced lock and unlock calls");
      }

      unlockTimeSkippingLockedInternal();
    }
  }

  private static final Logger log = LoggerFactory.getLogger(SelfAdvancingTimerImpl.class);
  private final Clock systemClock;
  private final LongSupplier clock = this::currentTimeMillis;
  private final Lock lock = new ReentrantLock();
  private final Condition condition = lock.newCondition();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          5, 5, 1, TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> new Thread(r, "Timer task"));

  private long currentTimeMs;
  private int lockCount;
  // stores the last system clock time in ms used during time adjustment while time skipping is
  // locked.
  // if <> -1 - time is currently locked
  private long systemTimeMsLastAdvancedWhileLocked = -1;
  private boolean emptyQueue = true;

  private final PriorityQueue<TimerTask> tasks =
      new PriorityQueue<>(Comparator.comparing(TimerTask::getExecutionTime));
  private final Thread timerPump = new Thread(new TimerPump(), "SelfAdvancingTimer Pump");
  private LockHandle timeLockOnEmptyQueueHandle;

  // outstanding (unpaired) lock / unlock events. Debugging purposes only.
  private final LinkedList<LockEvent> lockEvents = new LinkedList<>();

  public SelfAdvancingTimerImpl(long initialTimeMs, Clock systemClock) {
    this.systemClock = systemClock;
    currentTimeMs = initialTimeMs == 0 ? systemClock.millis() : initialTimeMs;
    log.trace("Current time on start: {}", currentTimeMs);
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    // Queue is initially empty. The code assumes that in this case skipping is already locked.
    timeLockOnEmptyQueueHandle = lockTimeSkipping("SelfAdvancingTimerImpl constructor empty-queue");
    timerPump.start();
  }

  private void updateTimeLocked() {
    if (lockCount > 0) {
      if (systemTimeMsLastAdvancedWhileLocked < 0) {
        throw new IllegalStateException("Invalid systemTimeLastLocked");
      }
      long systemTime = systemClock.millis();
      currentTimeMs = currentTimeMs + (systemTime - systemTimeMsLastAdvancedWhileLocked);
      systemTimeMsLastAdvancedWhileLocked = systemTime;
    } else {
      TimerTask task = tasks.peek();
      if (task != null && !task.isCanceled() && task.getExecutionTime() > currentTimeMs) {
        currentTimeMs = task.getExecutionTime();
        log.trace("Jumping to the time of the next timer task: {}", currentTimeMs);
      }
    }
  }

  private long currentTimeMillis() {
    lock.lock();
    try {
      {
        updateTimeLocked();
        return currentTimeMs;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Functions.Proc schedule(Duration delay, Runnable task) {
    return schedule(delay, task, "unknown");
  }

  @Override
  public Functions.Proc schedule(Duration delay, Runnable task, String taskInfo) {
    lock.lock();
    try {
      // get a last time in case the last TimePump update was relatively long time ago
      updateTimeLocked();
      long executionTime = delay.toMillis() + currentTimeMs;
      return scheduleAtLocked(executionTime, task, taskInfo);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Functions.Proc scheduleAt(Instant timestamp, Runnable task, String taskInfo) {
    lock.lock();
    try {
      // get a last time in case the last TimePump update was relatively long time ago
      updateTimeLocked();
      return scheduleAtLocked(timestamp.toEpochMilli(), task, taskInfo);
    } finally {
      lock.unlock();
    }
  }

  private Functions.Proc scheduleAtLocked(long timestampMs, Runnable task, String taskInfo) {
    TimerTask timerTask = new TimerTask(timestampMs, task, taskInfo);
    Functions.Proc cancellationHandle = timerTask::cancel;
    tasks.add(timerTask);
    log.trace("Scheduling task <{}> for timestamp {}", taskInfo, timestampMs);
    // Locked when queue became empty
    if (tasks.size() == 1 && emptyQueue) {
      if (timeLockOnEmptyQueueHandle == null) {
        throw new IllegalStateException(
            "SelfAdvancingTimerImpl should take a lock and get a handle when queue is empty, but handle is null");
      }
      timeLockOnEmptyQueueHandle.unlock(
          "SelfAdvancingTimerImpl schedule non-empty-queue, task: " + taskInfo);
      timeLockOnEmptyQueueHandle = null;
      emptyQueue = false;
    }
    condition.signal();
    return cancellationHandle;
  }

  /** @return Supplier that returns time in milliseconds when called. */
  @Override
  public LongSupplier getClock() {
    return clock;
  }

  @Override
  public LockHandle lockTimeSkipping(String caller) {
    lock.lock();
    try {
      return lockTimeSkippingLocked(caller);
    } finally {
      lock.unlock();
    }
  }

  private LockHandle lockTimeSkippingLocked(String caller) {
    if (lockCount++ == 0) {
      systemTimeMsLastAdvancedWhileLocked = systemClock.millis();
    }
    LockEvent event = new LockEvent(caller, LockEventType.LOCK);
    lockEvents.add(event);
    return new TimerLockHandle(event);
  }

  @Override
  public void unlockTimeSkipping(String caller) {
    lock.lock();
    try {
      unlockTimeSkippingLocked(caller);
      condition.signal();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Instant skip(Duration duration) {
    lock.lock();
    try {
      currentTimeMs += duration.toMillis();
      log.trace("Skipping time by {} to: {}", duration, currentTimeMs);
      condition.signal();
      return Instant.ofEpochMilli(currentTimeMs);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void skipTo(Instant timestamp) {
    lock.lock();
    try {
      if (timestamp.toEpochMilli() > currentTimeMs) {
        log.trace("Skipping time from {} to: {}", currentTimeMs, timestamp.toEpochMilli());
        currentTimeMs = timestamp.toEpochMilli();
      } else {
        log.trace(
            "Time Skipping into past with timestamp {} was ignored because the current timestamp is {}",
            timestamp.toEpochMilli(),
            currentTimeMs);
      }
      condition.signal();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void updateLocks(List<RequestContext.TimerLockChange> updates) {
    lock.lock();
    try {
      for (RequestContext.TimerLockChange update : updates) {
        if (update.getChange() == 1) {
          lockTimeSkippingLocked(update.getCaller());
        } else {
          unlockTimeSkippingLocked(update.getCaller());
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void getDiagnostics(StringBuilder result) {
    result.append("Self Advancing Timer Lock Events:\n");
    lock.lock();
    try {
      int lockCount = 0;
      for (LockEvent event : lockEvents) {
        if (event.lockType == LockEventType.LOCK) {
          lockCount++;
        } else {
          lockCount--;
        }
        String indent = new String(new char[lockCount * 2]).replace("\0", " ");
        result
            .append(new Timestamp(event.timestamp))
            .append("\t")
            .append(event.lockType)
            .append("\t")
            .append(indent)
            .append(lockCount)
            .append("\t")
            .append(event.caller)
            .append("\n");
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void shutdown() {
    executor.shutdown();
    timerPump.interrupt();
    Uninterruptibles.joinUninterruptibly(timerPump);
  }

  private void unlockTimeSkippingLocked(String caller) {
    unlockTimeSkippingLockedInternal();
    lockEvents.add(new LockEvent(caller, LockEventType.UNLOCK));
  }

  private void unlockTimeSkippingLockedInternal() {
    if (lockCount == 0) {
      throw new IllegalStateException("Unbalanced lock and unlock calls: \n" + getDiagnostics());
    }
    lockCount--;
    if (lockCount == 0) {
      systemTimeMsLastAdvancedWhileLocked = -1;
    }
  }

  private String getDiagnostics() {
    StringBuilder result = new StringBuilder();
    getDiagnostics(result);
    return result.toString();
  }

  /**
   * TimerPump is very conservative with resources usage and if time is locked, it calculates the
   * time needed for the first scheduled task and waits for this calculated period to do the next
   * check. If the unit test is using mocked system clock that behaves differently from a wall
   * clock, this wait doesn't work and the test needs to trigger timer pump explicitly to forcefully
   * update the time and internal states.
   */
  @VisibleForTesting
  void pump() {
    lock.lock();
    try {
      condition.signal();
    } finally {
      lock.unlock();
    }
  }
}
