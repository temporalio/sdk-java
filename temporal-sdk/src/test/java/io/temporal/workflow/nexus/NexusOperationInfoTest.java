package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.NexusInfo;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusOperationInfoTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOperationHeaders() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    Assert.assertEquals(
        "UnitTest:" + testWorkflowRule.getTaskQueue(),
        workflowStub.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      // Try to call with the typed stub
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      return serviceStub.operation(input);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (context, details, input) -> {
            NexusInfo info = Nexus.getOperationContext().getInfo();
            return info.getNamespace() + ":" + info.getTaskQueue();
          });
    }
  }
}
