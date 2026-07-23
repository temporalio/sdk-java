package io.temporal.nexus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.uber.m3.tally.NoopScope;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.internal.client.ActivityClientInternal;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * Pure unit tests for {@link TemporalNexusClientImpl#markAsyncOperationStarted()} semantics. These
 * run without a Temporal server (no {@link io.temporal.testing.internal.SDKTestWorkflowRule}).
 *
 * <p>The {@code markAsyncOperationStarted} guard fires as the very first statement in both {@code
 * startActivityImpl} and {@code invokeAndReturn}, so the second call always throws {@link
 * HandlerException}({@link HandlerException.ErrorType#BAD_REQUEST}) regardless of whether the first
 * call's downstream RPC succeeded.
 */
public class TemporalNexusClientImplTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String TASK_QUEUE = "test-task-queue";
  private static final String ENDPOINT = "test-endpoint";

  private TemporalNexusClientImpl client;
  private MockedStatic<ActivityClient> activityClientFactory;

  @Before
  public void setUp() {
    WorkflowClient workflowClient = mock(WorkflowClient.class);
    WorkflowClientOptions clientOptions = mock(WorkflowClientOptions.class);
    when(workflowClient.getOptions()).thenReturn(clientOptions);
    when(clientOptions.getNamespace()).thenReturn(NAMESPACE);
    when(clientOptions.getIdentity()).thenReturn("test-identity");
    when(workflowClient.getWorkflowServiceStubs()).thenReturn(mock(WorkflowServiceStubs.class));

    WorkflowClientInternal workflowClientInternal = mock(WorkflowClientInternal.class);
    when(workflowClient.getInternal()).thenReturn(workflowClientInternal);
    when(workflowClientInternal.startNexus(
            org.mockito.ArgumentMatchers.any(NexusStartWorkflowRequest.class),
            org.mockito.ArgumentMatchers.any(Functions.Proc.class)))
        .thenReturn(
            new NexusStartWorkflowResponse(
                WorkflowExecution.newBuilder()
                    .setWorkflowId("workflow-id")
                    .setRunId("workflow-run-id")
                    .build(),
                "workflow-operation-token"));
    when(workflowClient.newWorkflowStub(
            org.mockito.ArgumentMatchers.eq(BlockingWorkflow.class),
            org.mockito.ArgumentMatchers.any(WorkflowOptions.class)))
        .thenReturn(mock(BlockingWorkflow.class));

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
    internalCtx.setStartWorkflowResponseLink(Link.getDefaultInstance());
    CurrentNexusOperationContext.set(internalCtx);

    ActivityClient activityClient =
        mock(ActivityClient.class, withSettings().extraInterfaces(ActivityClientInternal.class));
    ActivityClientCallsInterceptor activityInvoker = mock(ActivityClientCallsInterceptor.class);
    when(((ActivityClientInternal) activityClient).getInvoker()).thenReturn(activityInvoker);
    when(activityInvoker.startActivity(
            org.mockito.ArgumentMatchers.any(
                ActivityClientCallsInterceptor.StartActivityInput.class)))
        .thenAnswer(
            invocation -> {
              CurrentNexusOperationContext.get().getNexusOperationMetadata().operationToken =
                  "activity-operation-token";
              ActivityClientCallsInterceptor.StartActivityInput input = invocation.getArgument(0);
              return new ActivityClientCallsInterceptor.StartActivityOutput(
                  input.getOptions().getId(), "activity-run-id");
            });
    activityClientFactory = mockStatic(ActivityClient.class);
    activityClientFactory
        .when(
            () ->
                ActivityClient.newInstance(
                    org.mockito.ArgumentMatchers.any(WorkflowServiceStubs.class),
                    org.mockito.ArgumentMatchers.any(ActivityClientOptions.class)))
        .thenReturn(activityClient);
  }

  @After
  public void tearDown() {
    activityClientFactory.close();
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

    client.startActivity(TestActivity.class, TestActivity::doSomething, options);

    // Second call: markAsyncOperationStarted() sees the flag and must throw BAD_REQUEST
    // immediately.
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

    client.startWorkflow(BlockingWorkflow.class, BlockingWorkflow::execute, "input", options);

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

    client.startWorkflow(BlockingWorkflow.class, BlockingWorkflow::execute, "in", wfOptions);

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

    client.startActivity(TestActivity.class, TestActivity::doSomething, actOptions);

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
