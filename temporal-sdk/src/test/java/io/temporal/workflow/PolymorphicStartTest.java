package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PolymorphicStartTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowAImpl.class, WorkflowBImpl.class)
          .build();

  @Test
  public void testPolymorphicStart() {
    WorkflowBase[] stubs =
        new WorkflowBase[] {
          testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowA.class),
          testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowB.class)
        };
    String results = stubs[0].execute("0") + ", " + stubs[1].execute("1");
    Assert.assertEquals("WorkflowAImpl0, WorkflowBImpl1", results);
  }

  public interface WorkflowBase {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface WorkflowA extends WorkflowBase {}

  @WorkflowInterface
  public interface WorkflowB extends WorkflowBase {}

  public static class WorkflowBImpl implements WorkflowB {
    @Override
    public String execute(String arg) {
      return "WorkflowBImpl" + arg;
    }
  }

  public static class WorkflowAImpl implements WorkflowA {
    @Override
    public String execute(String arg) {
      return "WorkflowAImpl" + arg;
    }
  }
}
