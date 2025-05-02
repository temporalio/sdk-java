package io.temporal.workflow.nexus;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SyncOperationTimeoutTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void typedOperationTimeout() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof TimeoutFailure);
    TimeoutFailure timeoutFailure = (TimeoutFailure) nexusFailure.getCause();
    Assert.assertEquals("operation timed out", timeoutFailure.getOriginalMessage());
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(1))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      // Try to call a synchronous operation in a blocking way
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      try {
        // Test calling a synchronous operation in a blocking way
        serviceStub.operation("test timeout");
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure e) {
        if (!(e.getCause() instanceof TimeoutFailure)) {
          throw e;
        }
        // Expected timeout
      }
      // Test calling a synchronous operation in a non-blocking way
      NexusOperationHandle<String> handle =
          Workflow.startNexusOperation(serviceStub::operation, "test timeout");
      try {
        handle.getExecution().get();
        Assert.fail("should not be reached");
      } catch (NexusOperationFailure e) {
        if (!(e.getCause() instanceof TimeoutFailure)) {
          throw e;
        }
        // Expected timeout
      }
      return handle.getResult().get();
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    int attempt = 0;

    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, name) -> {
            // Fail the first attempt with a retry-able exception. This tests
            // the schedule-to-close timeout applies across attempts.
            attempt += 1;
            if (attempt == 1) {
              throw new RuntimeException("test exception");
            }
            // Simulate a long-running operation
            try {
              Thread.sleep(6000);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            throw new RuntimeException("failed to call operation");
          });
    }
  }
}
