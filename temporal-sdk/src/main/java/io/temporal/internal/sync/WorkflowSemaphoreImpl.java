package io.temporal.internal.sync;

import static io.temporal.internal.sync.WorkflowInternal.assertNotReadOnly;

import com.google.common.base.Preconditions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.WorkflowSemaphore;
import java.time.Duration;

class WorkflowSemaphoreImpl implements WorkflowSemaphore {
  private int currentPermits;

  public WorkflowSemaphoreImpl(int permits) {
    this.currentPermits = permits;
  }

  @Override
  public void acquire() {
    acquire(1);
  }

  @Override
  public void acquire(int permits) {
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.acquire called with negative permits");
    WorkflowInternal.await(
        "WorkflowSemaphore.acquire",
        () -> {
          CancellationScope.throwCanceled();
          return currentPermits >= permits;
        });
    currentPermits -= permits;
  }

  @Override
  public boolean tryAcquire() {
    return tryAcquire(1);
  }

  @Override
  public boolean tryAcquire(Duration timeout) {
    return tryAcquire(1, timeout);
  }

  @Override
  public boolean tryAcquire(int permits) {
    assertNotReadOnly("WorkflowSemaphore.tryAcquire");
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.tryAcquire called with negative permits");
    if (currentPermits >= permits) {
      currentPermits -= permits;
      return true;
    }
    return false;
  }

  @Override
  public boolean tryAcquire(int permits, Duration timeout) {
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.tryAcquire called with negative permits");
    boolean acquired =
        WorkflowInternal.await(
            timeout,
            "WorkflowSemaphore.tryAcquire",
            () -> {
              CancellationScope.throwCanceled();
              return currentPermits >= permits;
            });
    if (acquired) {
      currentPermits -= permits;
    }
    return acquired;
  }

  @Override
  public void release() {
    release(1);
  }

  @Override
  public void release(int permits) {
    assertNotReadOnly("WorkflowSemaphore.release");
    Preconditions.checkArgument(
        permits > 0, "WorkflowSemaphore.release called with negative permits");
    currentPermits += permits;
  }
}
