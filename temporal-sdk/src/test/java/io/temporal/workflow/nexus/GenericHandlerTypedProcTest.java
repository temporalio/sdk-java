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
      for (int i = 0; i < 6; i++) {
        serviceStub.operation(i);
      }
      return "done";
    }
  }

  @Service
  public interface TestNexusServiceProc {
    @Operation
    Void operation(Integer input);
  }

  @ServiceImpl(service = TestNexusServiceProc.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Integer, Void> operation() {
      return TemporalOperationHandler.create(
          (context, client, input) -> {
            String prefix = "generic-handler-test-proc" + input + "-";
            String workflowId = prefix + context.getService() + "-" + context.getOperation();
            WorkflowOptions options =
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).build();
            switch (input) {
              case 0:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc::proc,
                    options);
              case 1:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test1ArgWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.Test1ArgWorkflowProc::proc1,
                    "input",
                    options);
              case 2:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test2ArgWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.Test2ArgWorkflowProc::proc2,
                    "input",
                    2,
                    options);
              case 3:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test3ArgWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.Test3ArgWorkflowProc::proc3,
                    "input",
                    2,
                    3,
                    options);
              case 4:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test4ArgWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.Test4ArgWorkflowProc::proc4,
                    "input",
                    2,
                    3,
                    4,
                    options);
              case 5:
                return client.startWorkflow(
                    TestMultiArgWorkflowFunctions.Test5ArgWorkflowProc.class,
                    TestMultiArgWorkflowFunctions.Test5ArgWorkflowProc::proc5,
                    "input",
                    2,
                    3,
                    4,
                    5,
                    options);
              default:
                throw new IllegalArgumentException("unexpected input: " + input);
            }
          });
    }
  }
}
