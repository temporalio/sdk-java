package io.temporal.client.functional;

import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityHandle;
import io.temporal.client.StartActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that {@link ActivityHandle#getResult()} continues polling after the server cuts a long
 * poll and returns an empty response. The server cuts activity long polls after approximately
 * {@value ACTIVITY_LONG_POLL_TIMEOUT_SECONDS} seconds; the activity here runs for 1.5× that so the
 * server-side cut fires at least once before the activity completes.
 *
 * <p>It has an async version in {@link GetActivityResultAsyncOverServerLongPollWaitTest} that is
 * split to reduce the total execution time.
 */
public class GetActivityResultSyncOverServerLongPollWaitTest {
  private static final int ACTIVITY_LONG_POLL_TIMEOUT_SECONDS = 20;

  @ActivityInterface
  public interface SlowActivity {
    @ActivityMethod(name = "SlowActivity")
    void run();
  }

  public static class SlowActivityImpl implements SlowActivity {
    @Override
    public void run() {
      try {
        Thread.sleep(Duration.ofSeconds(3 * ACTIVITY_LONG_POLL_TIMEOUT_SECONDS / 2).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setActivityImplementations(new SlowActivityImpl())
          .build();

  private ActivityClient newActivityClient() {
    return ActivityClient.newInstance(
        testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs(),
        ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
  }

  private StartActivityOptions slowOpts() {
    return StartActivityOptions.newBuilder()
        .setId("slow-act-" + UUID.randomUUID())
        .setTaskQueue(testWorkflowRule.getTaskQueue())
        .setScheduleToCloseTimeout(Duration.ofMinutes(2))
        .build();
  }

  @Test(timeout = 2 * ACTIVITY_LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResult() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    newActivityClient().execute(SlowActivity.class, SlowActivity::run, slowOpts());
  }
}
