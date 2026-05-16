package io.temporal.nexus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import java.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure unit tests for {@link TemporalNexusClientImpl#claimAsyncSlot()} semantics. These run without
 * a Temporal server (no {@link io.temporal.testing.internal.SDKTestWorkflowRule}).
 *
 * <p>The {@code claimAsyncSlot} guard fires as the very first statement in both {@code
 * startActivityImpl} and {@code invokeAndReturn}, so the second call always throws {@link
 * HandlerException}({@link HandlerException.ErrorType#BAD_REQUEST}) regardless of whether the first
 * call's downstream RPC succeeded.
 */
public class TemporalNexusClientImplTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String TASK_QUEUE = "test-task-queue";
  private static final String ENDPOINT = "test-endpoint";

  private TemporalNexusClientImpl client;

  @Before
  public void setUp() {
    // Mock WorkflowClient so constructor doesn't need a live server.
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowClientOptions clientOptions = mock(WorkflowClientOptions.class);
    when(workflowClient.getOptions()).thenReturn(clientOptions);
    when(clientOptions.getNamespace()).thenReturn(NAMESPACE);

    OperationContext operationContext = mock(OperationContext.class);
    when(operationContext.getService()).thenReturn("TestService");
    when(operationContext.getOperation()).thenReturn("testOperation");

    OperationStartDetails operationStartDetails =
        OperationStartDetails.newBuilder()
            .setCallbackUrl("http://localhost/callback")
            .setRequestId("test-request-id")
            .build();

    client = new TemporalNexusClientImpl(workflowClient, operationContext, operationStartDetails);

    // Set up the thread-local nexus context required by NexusStartActivityHelper and
    // NexusStartWorkflowHelper deep in the call stack.
    InternalNexusOperationContext internalCtx =
        new InternalNexusOperationContext(
            NAMESPACE, TASK_QUEUE, ENDPOINT, new NoopScope(), workflowClient);
    CurrentNexusOperationContext.set(internalCtx);
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  // ---------- Activity double-start ----------

  @Test
  public void doubleStartActivity_secondCallThrowsBadRequest() {
    StartActivityOptions options =
        StartActivityOptions.newBuilder()
            .setId("act-1")
            .setTaskQueue(TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    // First call: claimAsyncSlot() succeeds (sets flag), RPC may throw — we don't care.
    try {
      client.startActivity(TestActivity.class, TestActivity::doSomething, options);
    } catch (HandlerException e) {
      // If a HandlerException leaks out of the first call it must NOT be BAD_REQUEST
      // from claimAsyncSlot — that would mean the flag was already set before setUp.
      Assert.assertNotEquals(
          "First startActivity must not fail with BAD_REQUEST (claimAsyncSlot guard)",
          HandlerException.ErrorType.BAD_REQUEST,
          e.getErrorType());
    } catch (Exception ignored) {
      // Any other exception from the RPC layer is expected; the important thing is
      // claimAsyncSlot already ran and set asyncOperationStarted = true.
    }

    // Second call: claimAsyncSlot() sees the flag and must throw BAD_REQUEST immediately.
    HandlerException ex =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                client.startActivity(
                    TestActivity.class,
                    TestActivity::doSomething,
                    StartActivityOptions.newBuilder()
                        .setId("act-2")
                        .setTaskQueue(TASK_QUEUE)
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build()));

    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, ex.getErrorType());
    Assert.assertTrue(
        "Message should contain 'Only one async operation'",
        ex.getCause() instanceof IllegalStateException
            && ex.getCause()
                .getMessage()
                .startsWith("Only one async operation can be started per operation handler"));
  }

  // ---------- Workflow double-start ----------

  @Test
  public void doubleStartWorkflow_secondCallThrowsBadRequest() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setWorkflowId("wf-1").setTaskQueue(TASK_QUEUE).build();

    // First call: claimAsyncSlot() succeeds, downstream RPC may throw — we don't care.
    try {
      client.startWorkflow(BlockingWorkflow.class, BlockingWorkflow::execute, "input", options);
    } catch (HandlerException e) {
      Assert.assertNotEquals(
          "First startWorkflow must not fail with BAD_REQUEST (claimAsyncSlot guard)",
          HandlerException.ErrorType.BAD_REQUEST,
          e.getErrorType());
    } catch (Exception ignored) {
      // Expected: no live server.
    }

    // Second call must throw BAD_REQUEST immediately.
    HandlerException ex =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                client.startWorkflow(
                    BlockingWorkflow.class,
                    BlockingWorkflow::execute,
                    "input",
                    WorkflowOptions.newBuilder()
                        .setWorkflowId("wf-2")
                        .setTaskQueue(TASK_QUEUE)
                        .build()));

    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, ex.getErrorType());
    Assert.assertTrue(
        "Message should contain 'Only one async operation'",
        ex.getCause() instanceof IllegalStateException
            && ex.getCause()
                .getMessage()
                .startsWith("Only one async operation can be started per operation handler"));
  }

  // ---------- Mixed: workflow then activity ----------

  @Test
  public void startWorkflowThenActivity_activityThrowsBadRequest() {
    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder().setWorkflowId("wf-mixed-1").setTaskQueue(TASK_QUEUE).build();

    try {
      client.startWorkflow(BlockingWorkflow.class, BlockingWorkflow::execute, "in", wfOptions);
    } catch (HandlerException e) {
      Assert.assertNotEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    } catch (Exception ignored) {
    }

    HandlerException ex =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                client.startActivity(
                    TestActivity.class,
                    TestActivity::doSomething,
                    StartActivityOptions.newBuilder()
                        .setId("act-mixed-1")
                        .setTaskQueue(TASK_QUEUE)
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build()));

    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, ex.getErrorType());
  }

  // ---------- Mixed: activity then workflow ----------

  @Test
  public void startActivityThenWorkflow_workflowThrowsBadRequest() {
    StartActivityOptions actOptions =
        StartActivityOptions.newBuilder()
            .setId("act-mixed-2")
            .setTaskQueue(TASK_QUEUE)
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    try {
      client.startActivity(TestActivity.class, TestActivity::doSomething, actOptions);
    } catch (HandlerException e) {
      Assert.assertNotEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    } catch (Exception ignored) {
    }

    HandlerException ex =
        Assert.assertThrows(
            HandlerException.class,
            () ->
                client.startWorkflow(
                    BlockingWorkflow.class,
                    BlockingWorkflow::execute,
                    "in",
                    WorkflowOptions.newBuilder()
                        .setWorkflowId("wf-mixed-2")
                        .setTaskQueue(TASK_QUEUE)
                        .build()));

    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, ex.getErrorType());
  }

  // ---------- Minimal stubs ----------

  @io.temporal.activity.ActivityInterface
  public interface TestActivity {
    @io.temporal.activity.ActivityMethod
    void doSomething();
  }

  @io.temporal.workflow.WorkflowInterface
  public interface BlockingWorkflow {
    @io.temporal.workflow.WorkflowMethod
    String execute(String input);
  }

  public static class BlockingWorkflowImpl implements BlockingWorkflow {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
