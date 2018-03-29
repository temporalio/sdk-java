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
import java.time.Duration;
import java.util.Comparator;
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

    TimerTask(long executionTime, Runnable runnable) {
      this.executionTime = executionTime;
      this.runnable = runnable;
    }

    long getExecutionTime() {
      return executionTime;
    }

    public Runnable getRunnable() {
      return runnable;
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
          lockTimeSkippingLocked(); // Switching to wall time when no tasks scheduled
          emptyQueue = true;
        }
        TimerTask peekedTask = tasks.peek();
        if (peekedTask != null && peekedTask.getExecutionTime() <= currentTime) {
          try {
            lockTimeSkippingLocked();
            TimerTask polledTask = tasks.poll();
            Runnable runnable = polledTask.getRunnable();
            executor.execute(
                () -> {
                  try {
                    runnable.run();
                  } finally {
                    unlockTimeSkipping();
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

  private final PriorityQueue<TimerTask> tasks =
      new PriorityQueue<>(Comparator.comparing(TimerTask::getExecutionTime));
  private final Thread timerPump = new Thread(new TimerPump(), "SelfAdvancingTimer Pump");

  public SelfAdvancingTimerImpl(long initialTime) {
    currentTime = initialTime == 0 ? System.currentTimeMillis() : initialTime;
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    // Queue is initially empty. The code assumes that in this case skipping is already locked.
    lockTimeSkipping();
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
    lock.lock();
    try {
      {
        long executionTime = delay.toMillis() + currentTime;
        tasks.add(new TimerTask(executionTime, task));
        // Locked when queue became empty
        if (tasks.size() == 1 && emptyQueue) {
          unlockTimeSkippingLocked();
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
  public void lockTimeSkipping() {
    lock.lock();
    try {
      lockTimeSkippingLocked();
    } finally {
      lock.unlock();
    }
  }

  private void lockTimeSkippingLocked() {
    if (lockCount++ == 0) {
      timeLastLocked = currentTime;
      systemTimeLastLocked = System.currentTimeMillis();
    }
  }

  @Override
  public void unlockTimeSkipping() {
    lock.lock();
    try {
      unlockTimeSkippingLocked();
      condition.signal();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void updateLocks(int count) {
    lock.lock();
    try {
      if (count >= 0) {
        for (int i = 0; i < count; i++) {
          lockTimeSkippingLocked();
        }
      } else {
        for (int i = 0; i < -count; i++) {
          unlockTimeSkippingLocked();
        }
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

  private void unlockTimeSkippingLocked() {
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
