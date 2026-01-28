package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.nexus.Nexus;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

// Test an operation that takes and returns a protobuf message
public class ProtoOperationTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testDescribeWorkflowOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals(testWorkflowRule.getTaskQueue(), result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      TestNexusService serviceStub = Workflow.newNexusServiceStub(TestNexusService.class);

      WorkflowExecution exec =
          WorkflowExecution.newBuilder()
              .setWorkflowId(Workflow.getInfo().getWorkflowId())
              .setRunId(Workflow.getInfo().getRunId())
              .build();
      return serviceStub
          .describeWorkflow(
              DescribeWorkflowExecutionRequest.newBuilder()
                  .setNamespace(Workflow.getInfo().getNamespace())
                  .setExecution(exec)
                  .build())
          .getExecutionConfig()
          .getTaskQueue()
          .getName();
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    DescribeWorkflowExecutionResponse describeWorkflow(DescribeWorkflowExecutionRequest input);
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<DescribeWorkflowExecutionRequest, DescribeWorkflowExecutionResponse>
        describeWorkflow() {
      return OperationHandler.sync(
          (context, details, input) ->
              Nexus.getOperationContext()
                  .getWorkflowClient()
                  .getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkflowExecution(input));
    }
  }
}
