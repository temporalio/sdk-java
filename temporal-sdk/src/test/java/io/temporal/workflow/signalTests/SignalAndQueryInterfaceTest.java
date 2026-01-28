package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestWorkflows.SignalQueryBase;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalAndQueryInterfaceTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SignalQueryWorkflowAImpl.class).build();

  @Test
  public void testSignalAndQueryInterface() {
    SignalQueryWorkflowA stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SignalQueryWorkflowA.class);
    WorkflowExecution execution = WorkflowClient.start(stub::execute);

    SignalQueryBase signalStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(SignalQueryBase.class, execution.getWorkflowId());
    signalStub.signal("Hello World!");
    String result = WorkflowStub.fromTyped(stub).getResult(String.class);
    String queryResult = signalStub.getSignal();
    Assert.assertEquals("Hello World!", result);
    Assert.assertEquals(queryResult, result);
  }

  @WorkflowInterface
  public interface SignalQueryWorkflowA extends SignalQueryBase {
    @WorkflowMethod
    String execute();
  }

  public static class SignalQueryWorkflowAImpl implements SignalQueryWorkflowA {

    private String signal;

    @Override
    public void signal(String arg) {
      signal = arg;
    }

    @Override
    public String getSignal() {
      return signal;
    }

    @Override
    public String execute() {
      Workflow.await(() -> signal != null);
      return signal;
    }
  }
}
