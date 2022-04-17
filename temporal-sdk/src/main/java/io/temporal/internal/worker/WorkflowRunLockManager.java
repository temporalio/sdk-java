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

package io.temporal.internal.worker;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class WorkflowRunLockManager {
  // This is a single lock for the whole worker.
  // It's ok, because it's acquired for a very short time.
  // If this ever becomes a bottleneck, consider:
  //   - rework this lock to use com.google.common.util.concurrent.Striped and
  //   - make `perRunLock` a ConcurrentHashMap to increase parallelism of this code
  private final ReentrantLock mapLock = new ReentrantLock();
  private final Map<String, LockData> perRunLock = new HashMap<>();

  /**
   * @param runId to take a lock for
   * @param time the maximum time to wait for the lock
   * @param unit the time unit of the time argument
   * @return true if the lock is taken
   */
  boolean tryLock(String runId, long time, TimeUnit unit) throws InterruptedException {
    mapLock.lock();
    try {
      long timeoutNanos = unit.toNanos(time);
      LockData lockData = tryPersistLockByCurrentThreadLocked(runId);
      while (Thread.currentThread() != lockData.thread) {
        if (timeoutNanos <= 0) {
          return false;
        }
        timeoutNanos = lockData.condition.awaitNanos(timeoutNanos);
        lockData = tryPersistLockByCurrentThreadLocked(runId);
      }

      lockData.count++;
      return true;
    } finally {
      mapLock.unlock();
    }
  }

  private LockData tryPersistLockByCurrentThreadLocked(String runId) {
    return perRunLock.computeIfAbsent(
        runId, id -> new LockData(Thread.currentThread(), mapLock.newCondition()));
  }

  void unlock(String runId) {
    mapLock.lock();
    try {
      LockData lockData = perRunLock.get(runId);
      if (lockData == null) {
        throw new IllegalStateException("Lock for " + runId + " is not taken");
      }
      if (Thread.currentThread() == lockData.thread) {
        lockData.count--;
        if (lockData.count == 0) {
          perRunLock.remove(runId);
          // it's important to signal all threads,
          // otherwise n-1 of them will be stuck waiting on a condition that is not in the map
          // already
          lockData.condition.signalAll();
        }
      } else {
        throw new IllegalStateException(
            "Lock for "
                + runId
                + " is not acquired by the current thread "
                + Thread.currentThread().getName());
      }
    } finally {
      mapLock.unlock();
    }
  }

  @VisibleForTesting
  int totalLocks() {
    mapLock.lock();
    try {
      return perRunLock.size();
    } finally {
      mapLock.unlock();
    }
  }

  private static class LockData {
    final Thread thread;
    final Condition condition;
    // to make lock reentrant
    int count = 0;

    public LockData(Thread thread, Condition condition) {
      this.thread = thread;
      this.condition = condition;
    }
  }
}
