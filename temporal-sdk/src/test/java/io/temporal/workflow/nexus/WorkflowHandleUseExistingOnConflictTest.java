package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.*;

public class WorkflowHandleUseExistingOnConflictTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOnConflictUseExisting() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String workflowId = UUID.randomUUID().toString();
    String result = workflowStub.execute(workflowId);
    Assert.assertEquals("Hello from operation workflow " + workflowId, result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class);
      // Start asynchronous operations backed by a workflow
      List<NexusOperationHandle<String>> handles = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        handles.add(Workflow.startNexusOperation(serviceStub::operation, input));
      }
      // Wait for all operations to start
      for (NexusOperationHandle<String> handle : handles) {
        handle.getExecution().get();
      }

      // Signal the operation to unblock
      Workflow.newExternalWorkflowStub(OperationWorkflow.class, input).unblock();

      // Wait for all operations to complete
      String result = null;
      for (NexusOperationHandle<String> handle : handles) {
        result = handle.getResult().get();
        Assert.assertEquals("Hello from operation workflow " + input, result);
      }
      return result;
    }
  }

  @WorkflowInterface
  public interface OperationWorkflow {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void unblock();
  }

  public static class TestOperationWorkflow implements OperationWorkflow {
    boolean unblocked = false;

    @Override
    public String execute(String arg) {
      Workflow.await(() -> unblocked);
      return "Hello from operation workflow " + arg;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          AsyncWorkflowOperationTest.OperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(input)
                              .setWorkflowIdConflictPolicy(
                                  WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                              .build())
                  ::execute);
    }
  }
}
