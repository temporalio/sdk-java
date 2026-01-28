package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowHandle;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowHandleProcTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestNexus.class, TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceFuncImpl())
          .build();

  @Test
  public void handleTests() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("success", result);
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
      for (int i = 0; i < 7; i++) {
        serviceStub.operation(i);
      }
      return "success";
    }
  }

  @Service
  public interface TestNexusServiceProc {
    @Operation
    Void operation(Integer input);
  }

  @ServiceImpl(service = TestNexusServiceProc.class)
  public class TestNexusServiceFuncImpl {
    @OperationImpl
    public OperationHandler<Integer, Void> operation() {
      return WorkflowRunOperation.fromWorkflowHandle(
          (context, details, input) -> {
            WorkflowClient client = Nexus.getOperationContext().getWorkflowClient();
            switch (input) {
              case 0:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc);
              case 1:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test1ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc1,
                    "input");
              case 2:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test2ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc2,
                    "input",
                    2);
              case 3:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test3ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc3,
                    "input",
                    2,
                    3);
              case 4:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test4ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc4,
                    "input",
                    2,
                    3,
                    4);
              case 5:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test5ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc5,
                    "input",
                    2,
                    3,
                    4,
                    5);
              case 6:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test6ArgWorkflowProc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::proc6,
                    "input",
                    2,
                    3,
                    4,
                    5,
                    6);
              default:
                return null;
            }
          });
    }
  }
}
