package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

// Covers a very specific scenario where
// 1. the last completed workflow task has local activity markers
// 2. workflow execution is not completed
// 3. the workflow is evicted from the cache
// 4. workflow gets a "direct (legacy) query" to execute
// https://github.com/temporalio/sdk-java/issues/1190
public class QueryCausingReplayWithLocalActivityInLastWFTTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityAndQueryWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLocalActivityAndQuery() {
    TestWorkflows.TestWorkflowWithQuery workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    WorkflowExecution execution = WorkflowClient.start(workflowStub::execute);
    // Don't do waitForOKQuery wait here, it changes the structure of history events in a way that
    // doesn't reproduce the problem
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());

    testWorkflowRule.invalidateWorkflowCache();
    assertEquals("updated", workflowStub.query());
    assertEquals("done", WorkflowStub.fromTyped(workflowStub).getResult(String.class));

    // local activity is expected to be triggered only once
    activitiesImpl.assertInvocations("activity");
  }

  public static final class TestLocalActivityAndQueryWorkflow
      implements TestWorkflows.TestWorkflowWithQuery {

    String message = "initial";

    @Override
    public String execute() {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newLocalActivityOptions().toBuilder().build());
      localActivities.activity();
      message = "updated";
      Workflow.sleep(4_000);
      return "done";
    }

    @Override
    public String query() {
      return message;
    }
  }
}
