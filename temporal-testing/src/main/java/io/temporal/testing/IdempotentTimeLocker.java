package io.temporal.testing;

import io.grpc.Context;
import io.temporal.api.testservice.v1.LockTimeSkippingRequest;
import io.temporal.api.testservice.v1.UnlockTimeSkippingRequest;
import io.temporal.serviceclient.TestServiceStubs;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used to ensure that multiple TimeLockingWorkflowStubs that are blocked at the same time from
 * multiple threads execute unlock only once and the lock only once.
 */
class IdempotentTimeLocker {
  private final TestServiceStubs testServiceStubs;
  private final AtomicInteger count = new AtomicInteger(0);

  IdempotentTimeLocker(TestServiceStubs testServiceStubs) {
    this.testServiceStubs = testServiceStubs;
  }

  public void lockTimeSkipping() {
    int newCount = count.incrementAndGet();
    // perform an action only if we bring the counter to 0 (release of unlock)
    // or were the first who perform a lock
    if (newCount == 0 || newCount == 1) {
      Context.ROOT.run(
          () -> {
            // we want to ignore the gRPC deadline already existing in the context when we
            // communicate with the test server here to
            // 1. make sure that this operation is actually performed and
            // 2. more importantly, don't override and hide any underlying exceptions
            testServiceStubs
                .blockingStub()
                .lockTimeSkipping(LockTimeSkippingRequest.newBuilder().build());
          });
    }
  }

  public void unlockTimeSkipping() {
    int newCount = count.decrementAndGet();
    // perform an action only if we bring the counter to 0 (release of lock)
    // or were the first who perform an unlock
    if (newCount == 0 || newCount == -1) {
      Context.ROOT.run(
          () -> {
            // we want to ignore the gRPC deadline already existing in the context when we
            // communicate with the test server here to
            // 1. make sure that this operation is actually performed and
            // 2. more importantly, don't override and hide any underlying exceptions
            testServiceStubs
                .blockingStub()
                .unlockTimeSkipping(UnlockTimeSkippingRequest.newBuilder().build());
          });
    }
  }
}
