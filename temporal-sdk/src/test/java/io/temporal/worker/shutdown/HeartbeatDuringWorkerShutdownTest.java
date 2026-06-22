package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.ActivityHandle;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link WorkerOptions.Builder#setAllowActivityHeartbeatDuringShutdown(boolean)}. Gated
 * behind {@link SDKTestWorkflowRule#useExternalService} because the embedded test server may not
 * support the standalone activity APIs.
 */
public class HeartbeatDuringWorkerShutdownTest {

  private static final String EXPECTED_RESULT = "completed";

  private final Semaphore activityStarted = new Semaphore(0);
  private final Semaphore shutdownTriggered = new Semaphore(0);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(60)
          .setWorkerOptions(
              WorkerOptions.newBuilder().setAllowActivityHeartbeatDuringShutdown(true).build())
          .setActivityImplementations(
              new HeartbeatingActivityImpl(activityStarted, shutdownTriggered))
          .build();

  /**
   * Tests that when {@link WorkerOptions.Builder#setAllowActivityHeartbeatDuringShutdown(boolean)}
   * is enabled, heartbeats keep working after a graceful worker shutdown is initiated and the
   * activity runs to completion instead of getting an {@link
   * io.temporal.client.ActivityWorkerShutdownException}.
   */
  @Test
  public void testHeartbeatingActivityCompletesDuringShutdown() throws InterruptedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<String> handle = startHeartbeatingActivity();

    assertTrue(
        "Activity did not start within 30s", activityStarted.tryAcquire(30, TimeUnit.SECONDS));
    testWorkflowRule.getTestEnvironment().shutdown();
    shutdownTriggered.release();

    // a heartbeat failure would fail the activity and make getResult throw
    assertEquals(EXPECTED_RESULT, handle.getResult());
    testWorkflowRule.getTestEnvironment().awaitTermination(30, TimeUnit.SECONDS);
  }

  /**
   * Tests that {@link WorkerOptions.Builder#setAllowActivityHeartbeatDuringShutdown(boolean)} is
   * ignored by {@link io.temporal.worker.WorkerFactory#shutdownNow()}: the heartbeat fails the
   * activity with an {@link io.temporal.client.ActivityWorkerShutdownException} instead of letting
   * it complete.
   */
  @Test
  public void testHeartbeatingActivityFailsDuringShutdownNow() throws InterruptedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<String> handle = startHeartbeatingActivity();

    assertTrue(
        "Activity did not start within 30s", activityStarted.tryAcquire(30, TimeUnit.SECONDS));
    testWorkflowRule.getTestEnvironment().shutdownNow();
    shutdownTriggered.release();

    // if heartbeating was incorrectly allowed, the activity would complete successfully and this
    // assertion would fail
    ActivityFailedException ex = assertThrows(ActivityFailedException.class, handle::getResult);
    // the heartbeat is rejected with ActivityWorkerShutdownException, which crosses the server
    // boundary as an ApplicationFailure whose type is the exception's class name
    assertEquals(
        ActivityWorkerShutdownException.class.getName(),
        ((ApplicationFailure) ex.getCause()).getType());
    testWorkflowRule.getTestEnvironment().awaitTermination(30, TimeUnit.SECONDS);
  }

  private ActivityHandle<String> startHeartbeatingActivity() {
    ActivityClient client =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
    StartActivityOptions options =
        StartActivityOptions.newBuilder()
            .setId("heartbeat-during-shutdown-" + UUID.randomUUID())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build();
    return client.start(HeartbeatingActivity.class, HeartbeatingActivity::execute, options);
  }

  @ActivityInterface
  public interface HeartbeatingActivity {
    @ActivityMethod
    String execute();
  }

  public static class HeartbeatingActivityImpl implements HeartbeatingActivity {
    private final Semaphore activityStarted;
    private final Semaphore shutdownTriggered;

    public HeartbeatingActivityImpl(Semaphore activityStarted, Semaphore shutdownTriggered) {
      this.activityStarted = activityStarted;
      this.shutdownTriggered = shutdownTriggered;
    }

    @Override
    public String execute() {
      activityStarted.release();
      try {
        if (!shutdownTriggered.tryAcquire(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Worker shutdown was not triggered within 30s");
        }
      } catch (InterruptedException e) {
        // we ignore the interruption issued by shutdownNow and proceed to the heartbeat below,
        // which is the signal under test
      }
      Activity.getExecutionContext().heartbeat("progress");
      return EXPECTED_RESULT;
    }
  }
}
