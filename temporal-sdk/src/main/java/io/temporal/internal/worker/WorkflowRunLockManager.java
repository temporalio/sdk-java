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

package io.temporal.internal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class WorkflowRunLockManager {
  private final Map<String, RefCountedLock> runIdLock = new ConcurrentHashMap<>();

  public boolean tryLock(String runId, long timeout, TimeUnit unit) throws InterruptedException {
    RefCountedLock runLock =
        runIdLock.compute(
            runId,
            (id, lock) -> {
              if (lock == null) {
                lock = new RefCountedLock();
              }
              lock.refCount++;
              return lock;
            });

    boolean obtained = false;
    try {
      obtained = runLock.lock.tryLock(timeout, unit);
      return obtained;
    } finally {
      if (!obtained) {
        derefAndUnlock(runId, false);
      }
    }
  }

  public void unlock(String runId) {
    derefAndUnlock(runId, true);
  }

  private void derefAndUnlock(String runId, boolean unlock) {
    runIdLock.compute(
        runId,
        (id, runLock) -> {
          Preconditions.checkState(
              runLock != null,
              "Thread '%s' doesn't have an acquired lock for runId '%s'",
              Thread.currentThread().getName(),
              runId);
          if (unlock) {
            runLock.lock.unlock();
          }
          return --runLock.refCount == 0 ? null : runLock;
        });
  }

  @VisibleForTesting
  int totalLocks() {
    return runIdLock.size();
  }

  private static class RefCountedLock {
    final ReentrantLock lock = new ReentrantLock();
    int refCount = 0;
  }
}
