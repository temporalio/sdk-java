package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

public class UntypedSyncOperationStubTest {
  @ClassRule
  public static SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void untypedNexusServiceStub() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello, " + testWorkflowRule.getTaskQueue() + "!", result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String name) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(testWorkflowRule.getNexusEndpoint().getSpec().getName())
              .setOperationOptions(options)
              .build();
      NexusServiceStub serviceStub =
          Workflow.newUntypedNexusServiceStub("TestNexusService1", serviceOptions);
      String syncResult = serviceStub.execute("operation", String.class, name);
      // Try to call a synchronous operation in a non-blocking way
      Promise<String> syncPromise = serviceStub.executeAsync("operation", String.class, name);
      Assert.assertEquals(syncPromise.get(), syncResult);
      // Try to call a synchronous operation in a non-blocking way using a handle
      NexusOperationHandle<String> syncOpHandle =
          serviceStub.start("operation", String.class, name);
      NexusOperationExecution syncOpExec = syncOpHandle.getExecution().get();
      // Execution id is not present for synchronous operations
      if (syncOpExec.getOperationToken().isPresent()) {
        Assert.fail("Execution id is present");
      }
      // Result should always be completed for a synchronous operations when the Execution promise
      // is resolved
      if (!syncOpHandle.getResult().isCompleted()) {
        Assert.fail("Result is not completed");
      }

      return syncResult;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
    }
  }
}
