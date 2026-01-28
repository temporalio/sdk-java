package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.common.converter.EncodedValuesTest;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

// Test an operation that takes and returns a List type with a non-primitive element type
public class GenericListOperationTest {
  @ClassRule
  public static SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("hello", result);
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
      List<EncodedValuesTest.Pair> arg =
          Collections.singletonList(new EncodedValuesTest.Pair(1, "hello"));
      return serviceStub.operation(arg).get(0).getS();
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    List<EncodedValuesTest.Pair> operation(List<EncodedValuesTest.Pair> input);
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<List<EncodedValuesTest.Pair>, List<EncodedValuesTest.Pair>>
        operation() {
      return OperationHandler.sync(
          (context, details, input) -> {
            return input;
          });
    }
  }
}
