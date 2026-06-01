package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link RootWorkflowClientInvoker#signal} link propagation in and out of the Nexus
 * operation context. These run against mocked dependencies and exercise the code paths that the
 * integration tests in {@code SignalOperationLinkingTest} can only cover when a real flag-enabled
 * server is available.
 */
public class RootWorkflowClientInvokerLinkPropagationTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String WORKFLOW_ID = "wf-target";

  private GenericWorkflowClient genericClient;
  private RootWorkflowClientInvoker invoker;
  private InternalNexusOperationContext nexusCtx;

  @Before
  public void setUp() {
    genericClient = mock(GenericWorkflowClient.class);
    invoker =
        new RootWorkflowClientInvoker(
            genericClient,
            WorkflowClientOptions.newBuilder()
                .setNamespace(NAMESPACE)
                .validateAndBuildWithDefaults(),
            new WorkerFactoryRegistry());
    Scope metricsScope = new RootScopeBuilder().reportEvery(com.uber.m3.util.Duration.ofMillis(10));
    nexusCtx =
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", metricsScope, mock(WorkflowClient.class));
    CurrentNexusOperationContext.set(nexusCtx);
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  /**
   * Happy path against a flag-enabled server: inbound nexus links are forwarded onto the
   * SignalWorkflowExecutionRequest, and the response's backlink is captured back onto the operation
   * context.
   */
  @Test
  public void signalForwardsInboundLinksAndCapturesResponseBacklink() {
    Link inboundLink =
        workflowEventLink(
            "caller-wf", "caller-run", EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    nexusCtx.setNexusOperationLinks(Collections.singletonList(inboundLink));

    Link responseLink =
        workflowEventLink(
            WORKFLOW_ID, "target-run", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    SignalWorkflowExecutionResponse response =
        SignalWorkflowExecutionResponse.newBuilder().setLink(responseLink).build();
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class))).thenReturn(response);

    invoker.signal(newSignalInput());

    // Forward direction: the request the SDK sent carries the inbound link.
    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).signal(captor.capture());
    SignalWorkflowExecutionRequest sent = captor.getValue();
    Assert.assertEquals("request should carry the single inbound link", 1, sent.getLinksCount());
    Assert.assertEquals(inboundLink, sent.getLinks(0));

    // Backward direction: the response's link is now on the context for the task handler to read.
    List<Link> captured = nexusCtx.getBacklinks();
    Assert.assertEquals("expected one captured backlink", 1, captured.size());
    Assert.assertEquals(responseLink, captured.get(0));
  }

  /**
   * Older-server compatibility: the server returns a response without {@code link} set. The SDK
   * must not crash and must leave the operation context's backlink list empty.
   */
  @Test
  public void signalAgainstOlderServerCapturesNoBacklink() {
    Link inboundLink =
        workflowEventLink(
            "caller-wf", "caller-run", EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    nexusCtx.setNexusOperationLinks(Collections.singletonList(inboundLink));

    // Pre-1.31 server / flag-off server: response has no link.
    SignalWorkflowExecutionResponse response = SignalWorkflowExecutionResponse.getDefaultInstance();
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class))).thenReturn(response);

    invoker.signal(newSignalInput());

    // Forward direction still works regardless of server version.
    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).signal(captor.capture());
    Assert.assertEquals(1, captor.getValue().getLinksCount());

    // Backward direction: no backlink captured because the server didn't send one.
    Assert.assertTrue(
        "expected no captured backlink when server returned no link",
        nexusCtx.getBacklinks().isEmpty());
  }

  /**
   * Multi-signal: two signal RPCs in a row each contribute a backlink; both must be captured in
   * order on the context, ready for the task handler to drain into the operation response.
   */
  @Test
  public void multipleSignalsAccumulateAllBacklinks() {
    Link firstResponseLink =
        workflowEventLink("callee-a", "run-a", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    Link secondResponseLink =
        workflowEventLink("callee-b", "run-b", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class)))
        .thenReturn(SignalWorkflowExecutionResponse.newBuilder().setLink(firstResponseLink).build())
        .thenReturn(
            SignalWorkflowExecutionResponse.newBuilder().setLink(secondResponseLink).build());

    invoker.signal(newSignalInput());
    invoker.signal(newSignalInput());

    List<Link> captured = nexusCtx.getBacklinks();
    Assert.assertEquals(
        "expected one backlink per signal call",
        Arrays.asList(firstResponseLink, secondResponseLink),
        captured);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────

  private static WorkflowSignalInput newSignalInput() {
    return new WorkflowSignalInput(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build(),
        "test-signal",
        Header.empty(),
        new Object[] {"payload"});
  }

  private static Link workflowEventLink(String workflowId, String runId, EventType eventType) {
    return Link.newBuilder()
        .setWorkflowEvent(
            Link.WorkflowEvent.newBuilder()
                .setNamespace(NAMESPACE)
                .setWorkflowId(workflowId)
                .setRunId(runId)
                .setEventRef(
                    Link.WorkflowEvent.EventReference.newBuilder().setEventType(eventType)))
        .build();
  }
}
