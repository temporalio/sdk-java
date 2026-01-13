package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.internal.Signal;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class CancelWorkflowAsyncOperationTest extends BaseNexusTest {

  private static final Signal opStarted = new Signal();
  private static final Signal handlerFinished = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestCancelNexusOperationWorkflow.class,
              TestNexusCancellationType.class,
              WaitForCancelWorkflow.class)
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
    handlerFinished.clearSignal();
  }

  @Test
  public void cancelAsyncOperationWaitCompleted() {
    // Cancel before command is sent
    runCancelBeforeSentTest(NexusOperationCancellationType.WAIT_COMPLETED);

    // Cancel after operation is started
    WorkflowStub afterStartStub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestNexusOperationCancellationType");
    WorkflowExecution execution =
        afterStartStub.start(NexusOperationCancellationType.WAIT_COMPLETED, false);
    try {
      opStarted.waitForSignal();
    } catch (Exception e) {
      Assert.fail("test timed out waiting for operation to start.");
    }
    afterStartStub.cancel();
    Assert.assertThrows(WorkflowFailedException.class, () -> afterStartStub.getResult(Void.class));
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
    Assert.assertTrue(handlerFinished.isSignalled());
  }

  @Test
  public void cancelAsyncOperationWaitRequested() {
    // Cancel before command is sent
    runCancelBeforeSentTest(NexusOperationCancellationType.WAIT_REQUESTED);

    // Cancel after operation is started
    WorkflowStub stub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestNexusOperationCancellationType");
    WorkflowExecution execution = stub.start(NexusOperationCancellationType.WAIT_REQUESTED, false);
    try {
      opStarted.waitForSignal();
    } catch (Exception e) {
      Assert.fail("test timed out waiting for operation to start.");
    }
    stub.cancel();

    Assert.assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED);
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertFalse(handlerFinished.isSignalled());
  }

  @Test
  public void cancelAsyncOperationTryCancel() {
    // Cancel before command is sent
    runCancelBeforeSentTest(NexusOperationCancellationType.TRY_CANCEL);

    // Cancel after operation is started
    WorkflowStub stub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestNexusOperationCancellationType");
    WorkflowExecution execution = stub.start(NexusOperationCancellationType.TRY_CANCEL, false);
    try {
      opStarted.waitForSignal();
    } catch (Exception e) {
      Assert.fail("test timed out waiting for operation to start.");
    }
    stub.cancel();

    Assert.assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED);
    // Ideally, there would be an assertion that we did not wait for a
    // NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED event,
    // but the timing is so tight that it would make the test too flakey.
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertFalse(handlerFinished.isSignalled());
  }

  @Test
  public void cancelAsyncOperationAbandon() {
    // Cancel before command is sent
    runCancelBeforeSentTest(NexusOperationCancellationType.ABANDON);

    // For ABANDON, the handler workflow may start even in the cancel-before-sent case,
    // which can set opStarted and handlerFinished. Clear them before the after-start test.
    opStarted.clearSignal();
    handlerFinished.clearSignal();

    // Cancel after operation is started
    WorkflowStub stub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestNexusOperationCancellationType");
    WorkflowExecution execution = stub.start(NexusOperationCancellationType.ABANDON, false);
    try {
      opStarted.waitForSignal();
    } catch (Exception e) {
      Assert.fail("test timed out waiting for operation to start.");
    }
    stub.cancel();

    Assert.assertThrows(WorkflowFailedException.class, () -> stub.getResult(Void.class));

    testWorkflowRule.assertNoHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED);
    Assert.assertFalse(handlerFinished.isSignalled());
  }

  private void runCancelBeforeSentTest(NexusOperationCancellationType cancellationType) {
    WorkflowStub beforeSentStub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestNexusOperationCancellationType");
    beforeSentStub.start(cancellationType, true);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> beforeSentStub.getResult(Void.class));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof CanceledFailure);
    CanceledFailure canceledFailure = (CanceledFailure) nexusFailure.getCause();
    if (cancellationType == NexusOperationCancellationType.ABANDON) {
      Assert.assertEquals("operation canceled", canceledFailure.getOriginalMessage());
    } else {
      Assert.assertEquals(
          "operation canceled before it was started", canceledFailure.getOriginalMessage());
    }
  }

  @Test
  public void testCancelAsyncOperation() {
    WorkflowStub stub = testWorkflowRule.newUntypedWorkflowStub("TestWorkflow1");
    stub.start("cancel-replay-test");
    try {
      stub.getResult(String.class);
    } catch (Exception e) {
      Assert.fail("unexpected exception: " + e);
    }
  }

  @Test
  public void testCancelAsyncOperationReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testCancelNexusOperationHistory.json",
        CancelWorkflowAsyncOperationTest.TestCancelNexusOperationWorkflow.class);
  }

  public static class TestCancelNexusOperationWorkflow implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options = NexusOperationOptions.newBuilder().build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      TestNexusService serviceStub =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);
      try {
        Workflow.newCancellationScope(
                () -> {
                  NexusOperationHandle<Void> handle =
                      Workflow.startNexusOperation(serviceStub::operation, 0L);
                  handle.getExecution().get();
                  CancellationScope.current().cancel();
                  handle.getResult().get();
                })
            .run();
      } catch (NexusOperationFailure failure) {
        if (!(failure.getCause() instanceof CanceledFailure)) {
          throw failure;
        }
      }
      return Workflow.randomUUID().toString();
    }
  }

  public static class TestNexusCancellationType
      implements TestWorkflows.TestNexusOperationCancellationType {
    @Override
    public void execute(
        NexusOperationCancellationType cancellationType, boolean cancelImmediately) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .setCancellationType(cancellationType)
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusService serviceStub =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);

      NexusOperationHandle<Void> handle =
          Workflow.startNexusOperation(serviceStub::operation, 2000L);
      if (cancelImmediately) {
        CancellationScope.current().cancel();
      }
      handle.getExecution().get();
      opStarted.signal();

      // Wait to receive request to cancel, then block until operation result promise is ready.
      try {
        Workflow.await(() -> false);
      } catch (CanceledFailure f) {
        Workflow.newDetachedCancellationScope(
                () -> {
                  if (cancellationType == NexusOperationCancellationType.TRY_CANCEL
                      || cancellationType == NexusOperationCancellationType.WAIT_REQUESTED) {
                    // Use a timer to wake up the workflow so that it is not waiting on operation
                    // completion to get the next WFT.
                    Workflow.sleep(200);
                  }
                  handle.getResult().get();
                })
            .run();
      }
    }
  }

  @WorkflowInterface
  public interface DelayedFinishHandlerWorkflow {
    @WorkflowMethod
    Void execute(Long finishDelay);
  }

  public static class WaitForCancelWorkflow implements DelayedFinishHandlerWorkflow {
    @Override
    public Void execute(Long finishDelay) {
      if (finishDelay < 0) {
        return null;
      }
      try {
        Workflow.await(() -> false);
      } catch (CanceledFailure f) {
        if (finishDelay > 0) {
          Workflow.newDetachedCancellationScope(
                  () -> {
                    // Delay completion of handler workflow to confirm caller promise was unblocked
                    // at the right time.
                    Workflow.sleep(finishDelay);
                    handlerFinished.signal();
                  })
              .run();
        }
      }
      return null;
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    Void operation(Long finishDelay);
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Long, Void> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          DelayedFinishHandlerWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("test-" + details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
