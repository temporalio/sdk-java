package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.NexusServiceStub;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

// Test an operation that takes and returns a void type
public class VoidOperationTest {
  @ClassRule
  public static SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testVoidOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("success", result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(testWorkflowRule.getNexusEndpoint().getSpec().getName())
              .build();
      // Try to call with the typed stub
      TestNexusService serviceStub =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);
      serviceStub.noop();
      // Try to call with an untyped stub
      NexusServiceStub untypedServiceStub =
          Workflow.newUntypedNexusServiceStub("TestNexusService", serviceOptions);
      untypedServiceStub.execute("noop", Void.class, null);
      untypedServiceStub.execute("noop", Void.class, Void.class, null);
      return "success";
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    void noop();
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Void, Void> noop() {
      return OperationHandler.sync((context, details, input) -> null);
    }
  }
}
