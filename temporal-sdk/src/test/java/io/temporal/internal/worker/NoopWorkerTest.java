package io.temporal.internal.worker;

import static org.junit.Assert.*;

import io.temporal.testUtils.Eventually;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class NoopWorkerTest {
  @Test
  public void awaitTerminationWaitsForShutdown() throws Exception {
    NoopWorker worker = new NoopWorker();
    CompletableFuture<Boolean> awaitResult = new CompletableFuture<>();
    Thread waiter =
        new Thread(
            () -> {
              worker.awaitTermination(5, TimeUnit.SECONDS);
              awaitResult.complete(worker.isTerminated());
            });
    waiter.start();
    try {
      Eventually.assertEventually(
          Duration.ofSeconds(5),
          () ->
              assertTrue(awaitResult.isDone() || waiter.getState() == Thread.State.TIMED_WAITING));
      assertFalse("awaitTermination returned before shutdown", awaitResult.isDone());
      assertFalse(worker.isShutdown());
      assertFalse(worker.isTerminated());
    } finally {
      worker.shutdown(null, true).join();
      waiter.join(5_000);
    }
    assertTrue(awaitResult.get(5, TimeUnit.SECONDS));
    assertTrue(worker.isShutdown());
    // Repeated shutdown and waits after termination remain harmless.
    worker.shutdown(null, false).join();
    worker.awaitTermination(0, TimeUnit.SECONDS);
    assertTrue(worker.isTerminated());
  }

  @Test(timeout = 5_000)
  public void awaitTerminationRespectsTimeoutWithoutShuttingDown() {
    NoopWorker worker = new NoopWorker();
    worker.awaitTermination(0, TimeUnit.SECONDS);
    worker.awaitTermination(20, TimeUnit.MILLISECONDS);
    assertFalse(worker.isShutdown());
    assertFalse(worker.isTerminated());
  }

  @Test
  public void awaitTerminationPreservesInterruption() {
    NoopWorker worker = new NoopWorker();
    Thread.currentThread().interrupt();
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(worker.isShutdown());
      assertFalse(worker.isTerminated());
    } finally {
      Thread.interrupted();
    }
  }
}
