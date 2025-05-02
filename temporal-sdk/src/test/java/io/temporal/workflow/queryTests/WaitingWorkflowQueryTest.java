package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;

public class WaitingWorkflowQueryTest {

  private static final Signal QUERY_READY = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityAndQueryWorkflow.class)
          .build();

  @Test
  public void query() throws ExecutionException, InterruptedException {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(30))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflows.TestWorkflowWithQuery workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class, options);
    WorkflowClient.start(workflowStub::execute);

    QUERY_READY.waitForSignal();
    assertEquals("started", workflowStub.query());
  }

  public static final class TestLocalActivityAndQueryWorkflow
      implements TestWorkflows.TestWorkflowWithQuery {

    private String queryResult = "";

    @Override
    public String execute() {
      queryResult = "started";
      QUERY_READY.signal();
      Workflow.sleep(30_000);
      return "done";
    }

    @Override
    public String query() {
      return queryResult;
    }
  }
}
