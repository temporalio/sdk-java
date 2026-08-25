package io.temporal.activity;

import static org.junit.Assert.*;

import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityNextRetryDelayTest {

  private final NextRetryDelayActivityImpl activity = new NextRetryDelayActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(30)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(activity)
          .build();

  @Test
  public void activityNextRetryDelay() {
    TestWorkflowReturnDuration workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnDuration.class);
    Duration result = workflow.execute(false);
    Assert.assertTrue(result.toMillis() > 5000);
    Assert.assertEquals(4, activity.getAttemptCount());
  }

  @Test
  public void localActivityNextRetryDelay() {
    TestWorkflowReturnDuration workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnDuration.class);
    Duration result = workflow.execute(true);
    Assert.assertTrue(result.toMillis() > 5000);
    Assert.assertEquals(4, activity.getAttemptCount());
  }

  @WorkflowInterface
  public interface TestWorkflowReturnDuration {
    @WorkflowMethod
    Duration execute(boolean useLocalActivity);
  }

  public static class TestWorkflowImpl implements TestWorkflowReturnDuration {

    private static final RetryOptions FALLBACK_RETRY_OPTIONS =
        RetryOptions.newBuilder().setInitialInterval(Duration.ofMillis(100)).build();

    private final TestActivities.NoArgsActivity activities =
        Workflow.newActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose().toBuilder()
                .setRetryOptions(FALLBACK_RETRY_OPTIONS)
                .build());

    private final TestActivities.NoArgsActivity localActivities =
        Workflow.newLocalActivityStub(
            TestActivities.NoArgsActivity.class,
            SDKTestOptions.newLocalActivityOptions20sScheduleToClose().toBuilder()
                .setRetryOptions(FALLBACK_RETRY_OPTIONS)
                .build());

    @Override
    public Duration execute(boolean useLocalActivity) {
      long t1 = Workflow.currentTimeMillis();
      if (useLocalActivity) {
        localActivities.execute();
      } else {
        activities.execute();
      }
      long t2 = Workflow.currentTimeMillis();
      return Duration.ofMillis(t2 - t1);
    }
  }

  public static class NextRetryDelayActivityImpl implements TestActivities.NoArgsActivity {
    private final AtomicInteger attemptCount = new AtomicInteger();

    @Override
    public void execute() {
      attemptCount.incrementAndGet();
      int attempt = Activity.getExecutionContext().getInfo().getAttempt();
      if (attempt < 4) {
        throw ApplicationFailure.newFailureWithCauseAndDelay(
            "test retry delay failure " + attempt,
            "test failure",
            null,
            Duration.ofSeconds(attempt));
      }
    }

    public int getAttemptCount() {
      return attemptCount.get();
    }
  }
}
