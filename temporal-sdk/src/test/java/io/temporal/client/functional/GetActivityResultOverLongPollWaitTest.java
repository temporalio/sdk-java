package io.temporal.client.functional;

import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityHandle;
import io.temporal.client.StartActivityOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that {@link ActivityHandle#getResult()} and {@link ActivityHandle#getResultAsync()}
 * continue polling across the server's long-poll boundary and complete correctly for a standalone
 * activity that runs longer than one per-RPC timeout.
 */
public class GetActivityResultOverLongPollWaitTest {
  private static final int LONG_POLL_TIMEOUT_SECONDS = 5;

  @ActivityInterface
  public interface SlowActivity {
    @ActivityMethod(name = "SlowActivity")
    void run();
  }

  public static class SlowActivityImpl implements SlowActivity {
    @Override
    public void run() {
      try {
        Thread.sleep(Duration.ofSeconds(3 * LONG_POLL_TIMEOUT_SECONDS / 2).toMillis());
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

  private WorkflowServiceStubs clientStubs;
  private ActivityClient activityClient;

  @Before
  public void setUp() {
    WorkflowServiceStubsOptions options =
        testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs().getOptions();
    WorkflowServiceStubsOptions modifiedOptions =
        WorkflowServiceStubsOptions.newBuilder(options)
            .setRpcLongPollTimeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SECONDS))
            .build();
    clientStubs = WorkflowServiceStubs.newServiceStubs(modifiedOptions);
    activityClient =
        ActivityClient.newInstance(
            clientStubs,
            ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
  }

  @After
  public void tearDown() {
    clientStubs.shutdown();
  }

  private StartActivityOptions slowOpts() {
    return StartActivityOptions.newBuilder()
        .setId("slow-act-" + UUID.randomUUID())
        .setTaskQueue(testWorkflowRule.getTaskQueue())
        .setScheduleToCloseTimeout(Duration.ofMinutes(1))
        .build();
  }

  @Test(timeout = 2 * LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResult() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    activityClient.execute(SlowActivity.class, SlowActivity::run, slowOpts());
  }

  @Test(timeout = 2 * LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResultAsync() throws ExecutionException, InterruptedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    activityClient.executeAsync(SlowActivity.class, SlowActivity::run, slowOpts()).get();
  }
}
