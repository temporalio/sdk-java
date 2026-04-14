package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.Signal;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenericHandlerCancelTest extends BaseNexusTest {

  private static final Signal opStarted = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestCancelWorkflow.class, WaitForCancelWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Override
  public void setUp() {
    super.setUp();
    opStarted.clearSignal();
  }

  @Test
  public void cancelGenericHandlerOperation() {
    WorkflowStub stub = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestCancelWorkflow");
    stub.start(false);
    try {
      opStarted.waitForSignal();
    } catch (Exception e) {
      Assert.fail("test timed out waiting for operation to start.");
    }
    stub.cancel();
    Assert.assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));
    // Verify the nexus operation cancel was dispatched and completed successfully.
    // The parent workflow completes as cancelled (CanceledFailure), same as with
    // WorkflowRunOperation — the cancel handler correctly cancels the handler workflow.
    testWorkflowRule.assertHistoryEvent(
        stub.getExecution().getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
  }

  @WorkflowInterface
  public interface TestCancelWorkflowInterface {
    @WorkflowMethod(name = "TestCancelWorkflow")
    void execute(boolean cancelImmediately);
  }

  public static class TestCancelWorkflow implements TestCancelWorkflowInterface {
    @Override
    public void execute(boolean cancelImmediately) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .setCancellationType(NexusOperationCancellationType.WAIT_COMPLETED)
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();

      TestNexusCancelService serviceStub =
          Workflow.newNexusServiceStub(TestNexusCancelService.class, serviceOptions);

      NexusOperationHandle<Void> handle =
          Workflow.startNexusOperation(serviceStub::operation, "cancel-test");
      handle.getExecution().get();
      opStarted.signal();

      try {
        Workflow.await(() -> false);
      } catch (CanceledFailure f) {
        Workflow.newDetachedCancellationScope(() -> handle.getResult().get()).run();
      }
    }
  }

  @WorkflowInterface
  public interface WaitForCancelWorkflowInterface {
    @WorkflowMethod
    Void execute(String input);
  }

  public static class WaitForCancelWorkflow implements WaitForCancelWorkflowInterface {
    @Override
    public Void execute(String input) {
      try {
        Workflow.await(() -> false);
      } catch (CanceledFailure f) {
        // workflow was cancelled as expected
      }
      return null;
    }
  }

  @Service
  public interface TestNexusCancelService {
    @Operation
    Void operation(String input);
  }

  @ServiceImpl(service = TestNexusCancelService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, Void> operation() {
      return TemporalOperationHandler.from(
          (context, client, input) ->
              client.startWorkflow(
                  WaitForCancelWorkflowInterface.class,
                  wf -> wf.execute(input),
                  WorkflowOptions.newBuilder()
                      .setWorkflowId("generic-cancel-test-" + context.getService())
                      .build()));
    }
  }
}
