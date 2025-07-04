package io.temporal.activity;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflow.v1.PendingActivityInfo;
import io.temporal.api.workflowservice.v1.ResetActivityRequest;
import io.temporal.client.ActivityResetException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

public class ActivityResetTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new HeartBeatingActivityImpl())
          .build();

  @Test
  public void activityReset() {
    assumeTrue(
        "Test Server doesn't support activity pause", SDKTestWorkflowRule.useExternalService);

    TestWorkflows.TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowReturnString.class);
    Assert.assertEquals("I am stopped after reset", workflow.execute());
    Assert.assertEquals(
        1,
        WorkflowStub.fromTyped(workflow)
            .describe()
            .getRawDescription()
            .getPendingActivitiesCount());
    PendingActivityInfo activityInfo =
        WorkflowStub.fromTyped(workflow).describe().getRawDescription().getPendingActivities(0);
    Assert.assertEquals(
        "1",
        GlobalDataConverter.get()
            .fromPayload(
                activityInfo.getHeartbeatDetails().getPayloads(0), String.class, String.class));
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {

    private final TestActivities.TestActivity1 activities =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            SDKTestOptions.newActivityOptions20sScheduleToClose());

    @Override
    public String execute() {
      Async.function(activities::execute, "");
      Workflow.sleep(Duration.ofSeconds(1));
      return activities.execute("CompleteOnPause");
    }
  }

  public static class HeartBeatingActivityImpl implements TestActivities.TestActivity1 {
    private final AtomicInteger resetCounter = new AtomicInteger(0);

    @Override
    public String execute(String arg) {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      // Have the activity pause itself
      Activity.getExecutionContext()
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .resetActivity(
              ResetActivityRequest.newBuilder()
                  .setNamespace(info.getNamespace())
                  .setExecution(
                      WorkflowExecution.newBuilder()
                          .setWorkflowId(info.getWorkflowId())
                          .setRunId(info.getRunId())
                          .build())
                  .setId(info.getActivityId())
                  .build());
      while (true) {
        try {
          Thread.sleep(1000);
          // Check if the activity has been reset, and the activity info shows we are on the 1st
          // attempt.
          if (resetCounter.get() >= 1
              && Activity.getExecutionContext().getInfo().getAttempt() == 1) {
            return "I am stopped after reset";
          }
          // Heartbeat and verify that the correct exception is thrown
          Activity.getExecutionContext().heartbeat("1");
        } catch (ActivityResetException pe) {
          // Counter is incremented to track the number of resets
          resetCounter.addAndGet(1);
          // This will fail the attempt, and the activity will be retried.
          throw pe;
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}
