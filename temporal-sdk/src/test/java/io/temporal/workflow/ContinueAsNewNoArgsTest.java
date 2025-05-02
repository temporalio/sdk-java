package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewNoArgsTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestContinueAsNewNoArgsImpl.class).build();

  @Test
  public void testContinueAsNewNoArgs() {

    NoArgsWorkflow client = testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class);
    String result = client.execute();
    Assert.assertEquals("done", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method");
  }

  @WorkflowInterface
  public interface NoArgsWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class TestContinueAsNewNoArgsImpl implements NoArgsWorkflow {

    @Override
    public String execute() {
      NoArgsWorkflow next = Workflow.newContinueAsNewStub(NoArgsWorkflow.class);
      WorkflowInfo info = Workflow.getInfo();
      if (!info.getContinuedExecutionRunId().isPresent()) {
        next.execute();
        throw new RuntimeException("unreachable");
      } else {
        return "done";
      }
    }
  }
}
