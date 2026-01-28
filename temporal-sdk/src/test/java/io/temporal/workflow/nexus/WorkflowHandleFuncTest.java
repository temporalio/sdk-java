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
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowHandleFuncTest {
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
    Assert.assertEquals("funcinputinput2input23input234input2345input23456", result);
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

      TestNexusServiceFunc serviceStub =
          Workflow.newNexusServiceStub(TestNexusServiceFunc.class, serviceOptions);
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < 7; i++) {
        result.append(serviceStub.operation(i));
      }
      return result.toString();
    }
  }

  @Service
  public interface TestNexusServiceFunc {
    @Operation
    String operation(Integer input);
  }

  @ServiceImpl(service = TestNexusServiceFunc.class)
  public class TestNexusServiceFuncImpl {
    @OperationImpl
    public OperationHandler<Integer, String> operation() {
      return WorkflowRunOperation.fromWorkflowHandle(
          (context, details, input) -> {
            WorkflowClient client = Nexus.getOperationContext().getWorkflowClient();
            switch (input) {
              case 0:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func);
              case 1:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func1,
                    "input");
              case 2:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func2,
                    "input",
                    2);
              case 3:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test3ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func3,
                    "input",
                    2,
                    3);
              case 4:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test4ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func4,
                    "input",
                    2,
                    3,
                    4);
              case 5:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test5ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func5,
                    "input",
                    2,
                    3,
                    4,
                    5);
              case 6:
                return WorkflowHandle.fromWorkflowMethod(
                    client.newWorkflowStub(
                            TestMultiArgWorkflowFunctions.Test6ArgWorkflowFunc.class,
                            WorkflowOptions.newBuilder()
                                .setWorkflowId(details.getRequestId())
                                .build())
                        ::func6,
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
