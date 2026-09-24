package io.temporal.internal.worker;

import static org.junit.Assert.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class MultiThreadedPollerTest {

  @Test(timeout = 10000)
  public void shutdownDuringResubmissionPreservesTaskHandoff() throws Exception {
    assertRejectedResubmission(true, false, new Object());
  }

  @Test(timeout = 10000)
  public void shutdownDuringResubmissionAfterEmptyPoll() throws Exception {
    assertRejectedResubmission(true, false, null);
  }

  @Test(timeout = 10000)
  public void rejectionWhileRunningIsReported() throws Exception {
    assertRejectedResubmission(false, false, new Object());
  }

  @Test(timeout = 10000)
  public void interruptionDoesNotSuppressRejectionWhileRunning() throws Exception {
    assertRejectedResubmission(false, true, new Object());
  }

  private void assertRejectedResubmission(boolean shutdown, boolean interrupt, Object task)
      throws Exception {
    CountDownLatch pollStarted = new CountDownLatch(1);
    CountDownLatch finishPoll = new CountDownLatch(1);
    AtomicReference<Thread> pollThread = new AtomicReference<>();
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    AtomicInteger polls = new AtomicInteger();
    AtomicInteger resubmissions = new AtomicInteger();
    ShutdownableTaskExecutor<Object> taskExecutor = mock(ShutdownableTaskExecutor.class);
    PollerOptions options =
        PollerOptions.newBuilder()
            .setPollThreadNamePrefix("test-poller")
            .setPollerBehavior(new PollerBehaviorSimpleMaximum(1))
            .setUncaughtExceptionHandler((thread, error) -> uncaught.set(error))
            .build();
    MultiThreadedPoller<Object> poller =
        new MultiThreadedPoller<>(
            "test",
            () -> {
              polls.incrementAndGet();
              pollThread.set(Thread.currentThread());
              pollStarted.countDown();
              try {
                assertTrue(finishPoll.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
              }
              return task;
            },
            taskExecutor,
            options,
            new NoopScope(),
            new NamespaceCapabilities());

    assertTrue(poller.start());
    ExecutorService executor = poller.pollExecutor;
    try {
      assertTrue(pollStarted.await(5, TimeUnit.SECONDS));
      ExecutorService resubmissionExecutor = mock(ExecutorService.class, delegatesTo(executor));
      RejectedExecutionException rejection = new RejectedExecutionException("Executor is running");
      doAnswer(
              invocation -> {
                resubmissions.incrementAndGet();
                // Force shutdown after shouldTerminate() but before the executor accepts the task.
                if (shutdown) {
                  executor.shutdown();
                  executor.execute(invocation.getArgument(0));
                } else {
                  if (interrupt) {
                    Thread.currentThread().interrupt();
                  }
                  throw rejection;
                }
                return null;
              })
          .when(resubmissionExecutor)
          .execute(any(Runnable.class));
      poller.pollExecutor = resubmissionExecutor;
      finishPoll.countDown();

      // Join the thread so its uncaught exception handler has also finished.
      pollThread.get().join(5000);
      assertFalse(pollThread.get().isAlive());
      assertEquals(1, polls.get());
      assertEquals(1, resubmissions.get());
      if (shutdown) {
        assertNull(uncaught.get());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      } else {
        assertSame(rejection, uncaught.get());
      }
      if (task != null) {
        verify(taskExecutor).process(task);
      }
      verifyNoMoreInteractions(taskExecutor);
    } finally {
      finishPoll.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
