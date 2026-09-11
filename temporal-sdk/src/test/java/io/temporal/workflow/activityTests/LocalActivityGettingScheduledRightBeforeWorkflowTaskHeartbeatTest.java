package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.Config;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/1262")
public class LocalActivityGettingScheduledRightBeforeWorkflowTaskHeartbeatTest {
  private static final Duration WORKFLOW_TASK_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration SLEEP_DURATION =
      Duration.ofMillis(800); // << 1000 to avoid deadlock detection
  private static final int CONCURRENT_WORKFLOW_COUNT = 2;

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(HeartbeatingWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test(timeout = 15_000)
  public void testLocalActivitiesWorkflowTaskHeartbeat() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(WORKFLOW_TASK_TIMEOUT.multipliedBy(2))
            .setWorkflowTaskTimeout(WORKFLOW_TASK_TIMEOUT)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    List<WorkflowStub> stubs = new ArrayList<>();

    // Concurrent runs exercise the timing boundary without delaying their first workflow tasks
    // long enough to consume the workflow run timeout on an external service.
    for (int i = 0; i < CONCURRENT_WORKFLOW_COUNT; i++) {
      TestWorkflow1 workflow =
          testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);
      WorkflowStub stub = WorkflowStub.fromTyped(workflow);
      stub.start(testWorkflowRule.getTaskQueue());
      stubs.add(stub);
    }

    for (WorkflowStub stub : stubs) {
      assertEquals("done", stub.getResult(String.class));
    }
  }

  public static class HeartbeatingWorkflowImpl implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

      long firstLocalActivityDurationMs =
          (long) (WORKFLOW_TASK_TIMEOUT.toMillis() * Config.WORKFLOW_TASK_HEARTBEAT_COEFFICIENT)
              - SLEEP_DURATION.toMillis() / 2;
      localActivities.sleepActivity(firstLocalActivityDurationMs, 0);

      // It is very important for reproduction that the workflow heartbeat timeout is reached DURING
      // this sleep / workflow code execution.
      // So the first local activity is done, eventLoop is triggered and heartbeat timeout is
      // reached at the end of
      // this workflow code event loop call with the next activity scheduled.
      try {
        Thread.sleep(SLEEP_DURATION.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }

      localActivities.sleepActivity(TimeUnit.SECONDS.toMillis(1), 0);

      return "done";
    }
  }
}
