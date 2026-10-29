package io.temporal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.grpc.Status;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.internal.client.RootWorkflowClientInvoker;
import io.temporal.internal.client.WorkerFactoryRegistry;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import java.util.Optional;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Signals sent while a Temporal operation handler runs reuse the operation's request ID, so a
 * retried Nexus task dedupes against the first attempt. Because they share that ID, only one signal
 * is allowed per invocation.
 */
public class NexusSignalDeduplicationTest {
  private static final String REQUEST_ID = "nexus-op-request-id";
  private static final String WORKFLOW_ID = "wf-target";

  private GenericWorkflowClient genericClient;
  private WorkflowClientOptions options;
  private RootWorkflowClientInvoker invoker;
  private InternalNexusOperationContext nexusContext;

  @Before
  public void setUp() {
    genericClient = mock(GenericWorkflowClient.class);
    when(genericClient.signal(any()))
        .thenReturn(SignalWorkflowExecutionResponse.getDefaultInstance());
    when(genericClient.signalWithStart(any()))
        .thenReturn(SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("run").build());
    options =
        WorkflowClientOptions.newBuilder()
            .setNamespace("test-namespace")
            .validateAndBuildWithDefaults();
    invoker =
        new RootWorkflowClientInvoker(
            genericClient,
            options.getNamespace(),
            options.getIdentity(),
            options.getQueryRejectCondition(),
            options.getContextPropagators(),
            options.getDataConverter(),
            new WorkerFactoryRegistry());
    nexusContext =
        new InternalNexusOperationContext(
            options.getNamespace(), "tq", "endpoint", new NoopScope(), mock(WorkflowClient.class));
    CurrentNexusOperationContext.set(nexusContext);
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  private void enableSignalDeduplication() {
    nexusContext.setRequestId(REQUEST_ID);
    nexusContext.enableSignalDeduplication();
  }

  private void signal(String workflowId) {
    new WorkflowStubImpl(
            options,
            invoker,
            Optional.empty(),
            WorkflowExecution.newBuilder().setWorkflowId(workflowId).build(),
            false,
            null)
        .signal("test-signal", "payload");
  }

  private void signalWithStart(String workflowId) {
    new WorkflowStubImpl(
            options,
            invoker,
            "TestWorkflow",
            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue("tq").build())
        .signalWithStart("test-signal", new Object[] {"payload"}, new Object[] {});
  }

  private String sentSignalRequestId() {
    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient).signal(captor.capture());
    return captor.getValue().getRequestId();
  }

  private String sentSignalWithStartRequestId() {
    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient).signalWithStart(captor.capture());
    return captor.getValue().getRequestId();
  }

  private static void assertOneSignalRejection(Runnable second) {
    HandlerException e = Assert.assertThrows(HandlerException.class, second::run);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    Assert.assertEquals(
        "Only one signal can be sent per Nexus operation invocation.", e.getCause().getMessage());
  }

  @Test
  public void signalUsesOperationRequestId() {
    enableSignalDeduplication();
    signal(WORKFLOW_ID);
    Assert.assertEquals(REQUEST_ID, sentSignalRequestId());
  }

  @Test
  public void signalWithStartUsesOperationRequestId() {
    enableSignalDeduplication();
    signalWithStart(WORKFLOW_ID);
    Assert.assertEquals(REQUEST_ID, sentSignalWithStartRequestId());
  }

  /**
   * In a Nexus handler other than a Temporal operation start, signals keep generating their own
   * request ID and aren't limited to one.
   */
  @Test
  public void signalsWithoutDeduplicationKeepOwnRequestId() {
    nexusContext.setRequestId(REQUEST_ID);
    signal(WORKFLOW_ID);
    signalWithStart(WORKFLOW_ID);
    Assert.assertNotEquals(REQUEST_ID, sentSignalRequestId());
    Assert.assertNotEquals(REQUEST_ID, sentSignalWithStartRequestId());
  }

  /**
   * Any second signal or signalWithStart is rejected, whatever workflow it targets, since the
   * server could dedupe it against the first.
   */
  @Test
  public void onlyOneSignalPerInvocation() {
    Runnable[][] cases = {
      {() -> signal(WORKFLOW_ID), () -> signal(WORKFLOW_ID)},
      {() -> signal(WORKFLOW_ID), () -> signal("other-wf")},
      {() -> signal(WORKFLOW_ID), () -> signalWithStart(WORKFLOW_ID)},
      {() -> signalWithStart(WORKFLOW_ID), () -> signal(WORKFLOW_ID)},
      {() -> signalWithStart(WORKFLOW_ID), () -> signalWithStart(WORKFLOW_ID)},
    };
    enableSignalDeduplication();
    for (Runnable[] c : cases) {
      nexusContext.releaseSignal();
      clearInvocations(genericClient);
      c[0].run();
      assertOneSignalRejection(c[1]);
      // The rejected call must not reach the server.
      Assert.assertEquals(1, mockingDetails(genericClient).getInvocations().size());
    }
  }

  /**
   * A failed signal frees the slot so the handler can resend it, and the resend reuses the request
   * ID.
   */
  @Test
  public void failedSignalDoesNotConsumeSlot() {
    enableSignalDeduplication();
    when(genericClient.signal(any()))
        .thenThrow(Status.UNAVAILABLE.withDescription("try again").asRuntimeException())
        .thenReturn(SignalWorkflowExecutionResponse.getDefaultInstance());

    Assert.assertThrows(WorkflowServiceException.class, () -> signal(WORKFLOW_ID));
    signal(WORKFLOW_ID);

    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    verify(genericClient, times(2)).signal(captor.capture());
    for (SignalWorkflowExecutionRequest request : captor.getAllValues()) {
      Assert.assertEquals(REQUEST_ID, request.getRequestId());
    }
  }

  @Test
  public void failedSignalWithStartDoesNotConsumeSlot() {
    enableSignalDeduplication();
    when(genericClient.signalWithStart(any()))
        .thenThrow(Status.UNAVAILABLE.withDescription("try again").asRuntimeException())
        .thenReturn(SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("run").build());

    Assert.assertThrows(WorkflowServiceException.class, () -> signalWithStart(WORKFLOW_ID));
    signalWithStart(WORKFLOW_ID);

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient, times(2)).signalWithStart(captor.capture());
    for (SignalWithStartWorkflowExecutionRequest request : captor.getAllValues()) {
      Assert.assertEquals(REQUEST_ID, request.getRequestId());
    }
  }

  /**
   * A missing workflow ID is defaulted to a fresh UUID per call, so a retried Nexus task would
   * start a second workflow rather than dedupe against the first.
   */
  @Test
  public void signalWithStartRequiresWorkflowId() {
    enableSignalDeduplication();

    HandlerException e = Assert.assertThrows(HandlerException.class, () -> signalWithStart(null));
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, e.getErrorType());
    Assert.assertEquals(
        "signalWithStart from a Nexus operation handler requires a workflow ID.",
        e.getCause().getMessage());
    verify(genericClient, never()).signalWithStart(any());
  }
}
