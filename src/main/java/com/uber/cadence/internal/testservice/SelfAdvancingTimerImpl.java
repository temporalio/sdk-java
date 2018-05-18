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

package com.uber.cadence.internal.testservice;

import com.google.common.util.concurrent.Uninterruptibles;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
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
  }

  private class TimerPump implements Runnable {

    @Override
    public void run() {
      lock.lock();
      try {
        runLocked();
      } catch (Throwable e) {
        log.error("Timer pump failed", e);
      } finally {
        lock.unlock();
      }
    }

    private void runLocked() {
      while (!Thread.currentThread().isInterrupted()) {
        updateTimeLocked();
        if (!emptyQueue && tasks.isEmpty()) {
          lockTimeSkippingLocked("runLocked"); // Switching to wall time when no tasks scheduled
          emptyQueue = true;
        }
        TimerTask peekedTask = tasks.peek();
        if (peekedTask != null && peekedTask.getExecutionTime() <= currentTime) {
          try {
            LockHandle lockHandle = lockTimeSkippingLocked("runnable " + peekedTask.getTaskInfo());
            TimerTask polledTask = tasks.poll();
            Runnable runnable = polledTask.getRunnable();
            executor.execute(
                () -> {
                  try {
                    runnable.run();
                  } finally {
                    lockHandle.unlock();
                  }
                });
          } catch (Throwable e) {
            log.error("Timer task failure", e);
          }
          continue;
        }
        long timeToAwait =
            peekedTask == null ? Long.MAX_VALUE : peekedTask.getExecutionTime() - currentTime;
        try {
          condition.await(timeToAwait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          break;
        }
      }
    }
  }

  private static class LockEvent {
    String caller;
    LockEventType lockType;
    long timestamp;

    public LockEvent(String caller, LockEventType lockType) {
      this.caller = caller;
      this.lockType = lockType;
      timestamp = System.currentTimeMillis();
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
      lock.lock();
      try {
        unlockFromHandleLocked();
        condition.signal();
      } finally {
        lock.unlock();
      }
    }

    private void unlockFromHandleLocked() {
      Boolean removed = lockEvents.remove(event);
      if (!removed) {
        throw new IllegalStateException("Unbalanced lock and unlock calls");
      }

      unlockTimeSkippingLockedInternal();
    }
  }

  private static final Logger log = LoggerFactory.getLogger(SelfAdvancingTimerImpl.class);
  private final LongSupplier clock =
      () -> {
        long timeMillis = this.currentTimeMillis();
        return timeMillis;
      };
  private final Lock lock = new ReentrantLock();
  private final Condition condition = lock.newCondition();

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          5, 5, 1, TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> new Thread(r, "Timer task"));

  private long currentTime;
  private int lockCount;
  private long timeLastLocked = -1;
  private long systemTimeLastLocked = -1;
  private boolean emptyQueue = true;

  @SuppressWarnings("JdkObsolete")
  private final LinkedList<LockEvent> lockEvents = new LinkedList<>();

  private final PriorityQueue<TimerTask> tasks =
      new PriorityQueue<>(Comparator.comparing(TimerTask::getExecutionTime));
  private final Thread timerPump = new Thread(new TimerPump(), "SelfAdvancingTimer Pump");

  public SelfAdvancingTimerImpl(long initialTime) {
    currentTime = initialTime == 0 ? System.currentTimeMillis() : initialTime;
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    // Queue is initially empty. The code assumes that in this case skipping is already locked.
    lockTimeSkipping("SelfAdvancingTimerImpl constructor");
    timerPump.start();
  }

  private void updateTimeLocked() {
    if (lockCount > 0) {
      if (timeLastLocked < 0 || systemTimeLastLocked < 0) {
        throw new IllegalStateException("Invalid timeLastLocked or systemTimeLastLocked");
      }
      currentTime = timeLastLocked + (System.currentTimeMillis() - systemTimeLastLocked);
    } else {
      TimerTask task = tasks.peek();
      if (task != null && task.getExecutionTime() > currentTime) {
        currentTime = task.getExecutionTime();
        log.trace("Jumping to the time of the next timer task: " + currentTime);
      }
    }
  }

  private long currentTimeMillis() {
    lock.lock();
    try {
      {
        updateTimeLocked();
        return currentTime;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void schedule(Duration delay, Runnable task) {
    schedule(delay, task, "unknown");
  }

  @Override
  public void schedule(Duration delay, Runnable task, String taskInfo) {
    lock.lock();
    try {
      {
        long executionTime = delay.toMillis() + currentTime;
        tasks.add(new TimerTask(executionTime, task, taskInfo));
        // Locked when queue became empty
        if (tasks.size() == 1 && emptyQueue) {
          unlockTimeSkippingLocked("schedule task for " + taskInfo);
          emptyQueue = false;
        }
        condition.signal();
      }
    } finally {
      lock.unlock();
    }
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
      timeLastLocked = currentTime;
      systemTimeLastLocked = System.currentTimeMillis();
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
  public void updateLocks(int count, String caller) {
    lock.lock();
    try {
      if (count >= 0) {
        for (int i = 0; i < count; i++) {
          lockTimeSkippingLocked("updateLocks " + caller);
        }
      } else {
        for (int i = 0; i < -count; i++) {
          unlockTimeSkippingLocked("updateLocks " + caller);
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
        result
            .append(new Timestamp(event.timestamp))
            .append("\t")
            .append(event.lockType)
            .append("\t")
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
      throw new IllegalStateException("Unbalanced lock and unlock calls");
    }
    lockCount--;
    if (lockCount == 0) {
      timeLastLocked = -1;
      systemTimeLastLocked = -1;
    }
  }
}
