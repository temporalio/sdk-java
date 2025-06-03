package io.temporal.workflow.queryTests;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.WorkflowLocal;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowInfoAndLocalInQueryTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflow.class).build();

  @Test
  public void queryReturnsInfoAndLocal() {
    TestWorkflows.TestWorkflowWithQuery workflowStub =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    WorkflowClient.start(workflowStub::execute);

    assertEquals("attempt=1 local=42", workflowStub.query());
    assertEquals("done", WorkflowStub.fromTyped(workflowStub).getResult(String.class));
  }

  public static class TestWorkflow implements TestWorkflows.TestWorkflowWithQuery {

    private final WorkflowLocal<Integer> local = WorkflowLocal.withCachedInitial(() -> 0);

    @Override
    public String execute() {
      local.set(42);
      Workflow.sleep(Duration.ofSeconds(1));
      return "done";
    }

    @Override
    public String query() {
      WorkflowInfo info = Workflow.getInfo();
      return "attempt=" + info.getAttempt() + " local=" + local.get();
    }
  }
}
