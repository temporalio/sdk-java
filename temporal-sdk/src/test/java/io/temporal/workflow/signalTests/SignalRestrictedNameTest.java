package io.temporal.workflow.signalTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedSignalMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        SignalMethodWithOverrideNameRestrictedImpl.class));
    Assert.assertEquals(
        "signal name \"__temporal_signal\" must not start with \"__temporal_\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(SignalMethodNameRestrictedImpl.class));
    Assert.assertEquals(
        "signal name \"__temporal_signal\" must not start with \"__temporal_\"", e.getMessage());
  }

  @WorkflowInterface
  public interface SignalMethodWithOverrideNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @SignalMethod(name = "__temporal_signal")
    void signalMethod();
  }

  public static class SignalMethodWithOverrideNameRestrictedImpl
      implements SignalMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}

    @Override
    public void signalMethod() {}
  }

  @WorkflowInterface
  public interface SignalMethodNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @SignalMethod()
    void __temporal_signal();
  }

  public static class SignalMethodNameRestrictedImpl implements SignalMethodNameRestricted {
    @Override
    public void workflowMethod() {}

    @Override
    public void __temporal_signal() {}
  }
}
