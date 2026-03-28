package io.temporal.internal.worker;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests that an in-flight poll survives shutdown when graceful poll shutdown is enabled, and is
 * killed promptly when it is not.
 */
@RunWith(Parameterized.class)
public class GracefulPollShutdownTest {

  @Parameterized.Parameter public boolean graceful;

  @Parameterized.Parameters(name = "graceful={0}")
  public static Object[] data() {
    return new Object[] {true, false};
  }

  @Test(timeout = 10_000)
  public void inflightPollSurvivesShutdownOnlyWhenGraceful() throws Exception {
    NamespaceCapabilities capabilities = new NamespaceCapabilities();
    capabilities.setGracefulPollShutdown(graceful);

    AtomicReference<String> processedTask = new AtomicReference<>();
    CountDownLatch taskProcessedLatch = new CountDownLatch(1);
    ShutdownableTaskExecutor<String> taskExecutor =
        new ShutdownableTaskExecutor<String>() {
          @Override
          public void process(@NonNull String task) {
            processedTask.set(task);
            taskProcessedLatch.countDown();
          }

          @Override
          public boolean isShutdown() {
            return false;
          }

          @Override
          public boolean isTerminated() {
            return false;
          }

          @Override
          public CompletableFuture<Void> shutdown(
              ShutdownManager shutdownManager, boolean interruptTasks) {
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public void awaitTermination(long timeout, TimeUnit unit) {}
        };

    // -- poll task: first call returns immediately, second blocks until released --
    CountDownLatch secondPollStarted = new CountDownLatch(1);
    CountDownLatch releasePoll = new CountDownLatch(1);

    MultiThreadedPoller.PollTask<String> pollTask =
        new MultiThreadedPoller.PollTask<String>() {
          private int callCount = 0;

          @Override
          public synchronized String poll() {
            callCount++;
            if (callCount == 1) {
              return "task-1";
            } else if (callCount == 2) {
              secondPollStarted.countDown();
              try {
                releasePoll.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
              }
              return "task-2";
            }
            // Subsequent calls just block until interrupted (simulates long poll)
            try {
              Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return null;
          }
        };

    // -- create poller with 1 thread so polls are sequential --
    MultiThreadedPoller<String> poller =
        new MultiThreadedPoller<>(
            "test-identity",
            pollTask,
            taskExecutor,
            PollerOptions.newBuilder()
                .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
                .setPollThreadNamePrefix("test-poller")
                .build(),
            new NoopScope(),
            capabilities);

    poller.start();

    // Wait for the first task to be processed (proves poller is running)
    assertTrue("first task should be processed", taskProcessedLatch.await(5, TimeUnit.SECONDS));
    assertEquals("task-1", processedTask.get());

    // Wait for the second poll to be in-flight
    assertTrue("second poll should start", secondPollStarted.await(5, TimeUnit.SECONDS));

    // Trigger shutdown (don't interrupt tasks)
    ShutdownManager shutdownManager = new ShutdownManager();
    CompletableFuture<Void> shutdownFuture = poller.shutdown(shutdownManager, false);

    if (graceful) {
      // In graceful mode the poller waits for the in-flight poll to complete.
      // The shutdown should NOT have completed yet since the poll is still blocked.
      assertFalse("shutdown should not complete while poll is in-flight", shutdownFuture.isDone());

      // Simulate the server returning the poll response (as it would after ShutdownWorker RPC)
      releasePoll.countDown();

      // Wait for shutdown to complete - the poll should return "task-2" and be processed
      shutdownFuture.get(5, TimeUnit.SECONDS);

      assertEquals("task-2", processedTask.get());
    } else {
      // In legacy mode the poller forcefully interrupts in-flight polls.
      // Shutdown should complete quickly without releasing the blocked poll.
      shutdownFuture.get(5, TimeUnit.SECONDS);

      // The second task should NOT have been processed since the poll was killed.
      assertNotEquals(
          "task-2 should not be processed in legacy mode", "task-2", processedTask.get());
    }

    shutdownManager.close();
  }
}
