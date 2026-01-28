package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class QueryAfterStartFollowsRunsChainTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void testQueryAfterStartFollowsRunsChain() {
    TestWorkflows.TestWorkflowWithQuery workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    assertEquals("done", workflow.execute());
    assertEquals(
        "We expect query call to use the last run of the workflow, not the first one that we started by this stub",
        "2",
        workflow.query());
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowWithQuery {
    private final String status =
        Workflow.getInfo().getContinuedExecutionRunId().isPresent() ? "2" : "1";

    @Override
    public String execute() {
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        Workflow.continueAsNew();
      }
      return "done";
    }

    @Override
    public String query() {
      return status;
    }
  }
}
