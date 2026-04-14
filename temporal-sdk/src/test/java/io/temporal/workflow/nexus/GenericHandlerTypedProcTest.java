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

public class GenericHandlerTypedProcTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void typedProcStartWorkflowTest() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("done", result);
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

      TestNexusServiceProc serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceProc.class, serviceOptions);
      serviceStub.operation("input");
      return "done";
    }
  }

  @Service
  public interface TestNexusServiceProc {
    @Operation
    Void operation(String input);
  }

  @ServiceImpl(service = TestNexusServiceProc.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, Void> operation() {
      return TemporalOperationHandler.from(
          (context, client, input) ->
              client.startWorkflow(
                  TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc.class,
                  wf -> wf.proc(),
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(
                          "generic-handler-proc-"
                              + context.getService()
                              + "-"
                              + context.getOperation())
                      .build()));
    }
  }
}
