package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class QueryDuringContinueAsNewWorkflowTaskTest {

  private static final Signal LAST_WORKFLOW_TASK_STARTED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(30)
          .setWorkflowTypes(TestQueryDuringContinueAsNewWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void queryArrivingDuringWorkflowTaskThatContinuesAsNewIsAnswered()
      throws InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(30))
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestQueryDuringContinueAsNewWorkflow workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestQueryDuringContinueAsNewWorkflow.class, options);
    WorkflowClient.start(workflowStub::execute, 0);

    // The local activity keeps the workflow task that continues as new open while the query
    // arrives, so the server has to buffer the query until that task completes.
    LAST_WORKFLOW_TASK_STARTED.waitForSignal();
    assertEquals(0, workflowStub.getGeneration());
  }

  @WorkflowInterface
  public interface TestQueryDuringContinueAsNewWorkflow {

    @WorkflowMethod
    void execute(int generation);

    @QueryMethod
    int getGeneration();
  }

  public static class TestQueryDuringContinueAsNewWorkflowImpl
      implements TestQueryDuringContinueAsNewWorkflow {

    private int generation;

    @Override
    public void execute(int generation) {
      this.generation = generation;
      if (generation == 0) {
        VariousTestActivities localActivities =
            Workflow.newLocalActivityStub(
                VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());
        LAST_WORKFLOW_TASK_STARTED.signal();
        localActivities.sleepActivity(1000, 0);
        Workflow.continueAsNew(generation + 1);
      }
      Workflow.sleep(Duration.ofDays(1));
    }

    @Override
    public int getGeneration() {
      return generation;
    }
  }
}
