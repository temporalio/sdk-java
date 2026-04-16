package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenericHandlerUntypedStartWorkflowTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void untypedStartWorkflowTest() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("input", result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();

      TestNexusServiceUntyped serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceUntyped.class, serviceOptions);
      return serviceStub.operation("input");
    }
  }

  @Service
  public interface TestNexusServiceUntyped {
    @Operation
    String operation(String input);
  }

  @ServiceImpl(service = TestNexusServiceUntyped.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.from(
          (context, client, input) ->
              client.startWorkflow(
                  "func1",
                  String.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(
                          "generic-handler-untyped-"
                              + context.getService()
                              + "-"
                              + context.getOperation())
                      .build(),
                  input));
    }
  }
}
