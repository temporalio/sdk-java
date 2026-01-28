package io.temporal.workflow.updateTest;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UpdateRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedUpdateMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        UpdateMethodWithOverrideNameRestrictedImpl.class));
    Assert.assertEquals(
        "update name \"__temporal_update\" must not start with \"__temporal_\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(UpdateMethodNameRestrictedImpl.class));
    Assert.assertEquals(
        "update name \"__temporal_update\" must not start with \"__temporal_\"", e.getMessage());
  }

  @WorkflowInterface
  public interface UpdateMethodWithOverrideNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @UpdateMethod(name = "__temporal_update")
    void updateMethod();
  }

  public static class UpdateMethodWithOverrideNameRestrictedImpl
      implements UpdateMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}

    @Override
    public void updateMethod() {}
  }

  @WorkflowInterface
  public interface UpdateMethodNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @UpdateMethod()
    void __temporal_update();
  }

  public static class UpdateMethodNameRestrictedImpl implements UpdateMethodNameRestricted {
    @Override
    public void workflowMethod() {}

    @Override
    public void __temporal_update() {}
  }
}
