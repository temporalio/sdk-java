package io.temporal.internal.sync;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class ThreadStarvationVirtualThreadTest {

  @Test(timeout = 10_000)
  public void workflowThreadDoesNotStartWhenAllCarriersAreBusy() throws Exception {
    CountDownLatch carrierOccupied = new CountDownLatch(1);
    AtomicBoolean releaseCarrier = new AtomicBoolean();
    Thread carrierBlocker =
        Thread.ofVirtual()
            .name("carrier-blocker")
            .start(
                () -> {
                  carrierOccupied.countDown();
                  while (!releaseCarrier.get()) {
                    Thread.onSpinWait();
                  }
                });
    carrierOccupied.await();

    ExecutorService workflowThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    DeterministicRunner runner =
        DeterministicRunner.newRunner(
            workflowThreadExecutor::submit,
            DummySyncWorkflowContext.newDummySyncWorkflowContext(),
            () -> {
              throw new AssertionError("workflow code should not start while the carrier is busy");
            });

    try {
      PotentialDeadlockException e =
          assertThrows(PotentialDeadlockException.class, () -> runner.runUntilAllBlocked(100));
      assertTrue(e.getMessage().contains("could not start executing within 100ms"));
    } finally {
      workflowThreadExecutor.shutdownNow();
      releaseCarrier.set(true);
      carrierBlocker.join();
    }
  }
}
