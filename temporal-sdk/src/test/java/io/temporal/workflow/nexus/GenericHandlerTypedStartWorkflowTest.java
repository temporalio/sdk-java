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

public class GenericHandlerTypedStartWorkflowTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void typedStartWorkflowTests() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("funcinputinput2", result);
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

      TestNexusServiceGeneric serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceGeneric.class, serviceOptions);
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 3; i++) {
        result.append(serviceStub.operation(i));
      }
      return result.toString();
    }
  }

  @Service
  public interface TestNexusServiceGeneric {
    @Operation
    String operation(Integer input);
  }

  @ServiceImpl(service = TestNexusServiceGeneric.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Integer, String> operation() {
      return TemporalOperationHandler.from(
          (context, client, input) -> {
            switch (input) {
              case 0:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc.class,
                    wf -> wf.func(),
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(
                            "generic-handler-test-func0-"
                                + context.getService()
                                + "-"
                                + context.getOperation())
                        .build());
              case 1:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc.class,
                    wf -> wf.func1("input"),
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(
                            "generic-handler-test-func1-"
                                + context.getService()
                                + "-"
                                + context.getOperation())
                        .build());
              case 2:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc.class,
                    wf -> wf.func2("input", 2),
                    WorkflowOptions.newBuilder()
                        .setWorkflowId(
                            "generic-handler-test-func2-"
                                + context.getService()
                                + "-"
                                + context.getOperation())
                        .build());
              default:
                throw new IllegalArgumentException("unexpected input: " + input);
            }
          });
    }
  }
}
