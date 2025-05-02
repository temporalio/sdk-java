package io.temporal.internal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class WorkflowRunLockManager {
  private final Map<String, RefCountedLock> runIdLock = new ConcurrentHashMap<>();

  public boolean tryLock(String runId, long timeout, TimeUnit unit) throws InterruptedException {
    RefCountedLock runLock = obtainLock(runId);

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

  public boolean tryLock(String runId) {
    RefCountedLock runLock = obtainLock(runId);

    boolean obtained = false;
    try {
      obtained = runLock.lock.tryLock();
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

  private RefCountedLock obtainLock(String runId) {
    return runIdLock.compute(
        runId,
        (id, lock) -> {
          if (lock == null) {
            lock = new RefCountedLock();
          }
          lock.refCount++;
          return lock;
        });
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
