package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedWorkflowMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(WorkflowMethodWithOverrideNameImpl.class));
    Assert.assertEquals(
        "workflow name \"__temporal_workflow\" must not start with \"__temporal_\"",
        e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(WorkflowMethodRestrictedImpl.class));
    Assert.assertEquals(
        "workflow name \"__temporal_workflow\" must not start with \"__temporal_\"",
        e.getMessage());
  }

  @WorkflowInterface
  public interface WorkflowMethodWithOverrideNameRestricted {
    @WorkflowMethod(name = "__temporal_workflow")
    void workflowMethod();
  }

  public static class WorkflowMethodWithOverrideNameImpl
      implements WorkflowMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}
  }

  @WorkflowInterface
  public interface __temporal_workflow {
    @WorkflowMethod
    void workflowMethod();
  }

  public static class WorkflowMethodRestrictedImpl implements __temporal_workflow {
    @Override
    public void workflowMethod() {}
  }
}
