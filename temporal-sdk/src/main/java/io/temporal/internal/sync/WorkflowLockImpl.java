package io.temporal.internal.sync;

import static io.temporal.internal.sync.WorkflowInternal.assertNotReadOnly;

import com.google.common.base.Preconditions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.WorkflowLock;
import java.time.Duration;

class WorkflowLockImpl implements WorkflowLock {
  private boolean locked = false;

  @Override
  public void lock() {
    WorkflowInternal.await(
        "WorkflowLock.lock",
        () -> {
          CancellationScope.throwCanceled();
          return !locked;
        });
    locked = true;
  }

  @Override
  public boolean tryLock() {
    assertNotReadOnly("WorkflowLock.tryLock");
    if (!locked) {
      locked = true;
      return true;
    }
    return false;
  }

  @Override
  public boolean tryLock(Duration timeout) {
    boolean unlocked =
        WorkflowInternal.await(
            timeout,
            "WorkflowLock.tryLock",
            () -> {
              CancellationScope.throwCanceled();
              return !locked;
            });
    if (unlocked) {
      locked = true;
      return true;
    }
    return false;
  }

  @Override
  public void unlock() {
    assertNotReadOnly("WorkflowLock.unlock");
    Preconditions.checkState(locked, "WorkflowLock.unlock called when not locked");
    locked = false;
  }
}
