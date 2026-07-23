package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.update.v1.UpdateRef;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.StartUpdateInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationMetadata;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
   * SignalWorkflowExecutionRequest, and the response link is captured back onto the operation
   * context.
   */
  @Test
  public void signalForwardsInboundLinksAndCapturesResponseLink() {
    Link inboundLink =
        workflowEventLink(
            "caller-wf", "caller-run", EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    nexusCtx.setRequestLinks(Collections.singletonList(inboundLink));

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
    List<Link> captured = nexusCtx.getResponseLinks();
    Assert.assertEquals("expected one captured response link", 1, captured.size());
    Assert.assertEquals(responseLink, captured.get(0));
  }

  /**
   * Older-server compatibility: the server returns a response without {@code link} set. The SDK
   * must not crash and must leave the operation context's response link list empty.
   */
  @Test
  public void signalAgainstOlderServerCapturesNoResponseLink() {
    Link inboundLink =
        workflowEventLink(
            "caller-wf", "caller-run", EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    nexusCtx.setRequestLinks(Collections.singletonList(inboundLink));

    // Pre-1.31 server / flag-off server: response has no link.
    SignalWorkflowExecutionResponse response = SignalWorkflowExecutionResponse.getDefaultInstance();
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class))).thenReturn(response);

    invoker.signal(newSignalInput());

    // Forward direction still works regardless of server version.
    ArgumentCaptor<SignalWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).signal(captor.capture());
    Assert.assertEquals(1, captor.getValue().getLinksCount());

    // Backward direction: no response link captured because the server didn't send one.
    Assert.assertTrue(
        "expected no captured response link when server returned no link",
        nexusCtx.getResponseLinks().isEmpty());
  }

  /**
   * Multi-signal: two signal RPCs in a row each contribute a response link; both must be captured
   * in order on the context, ready for the task handler to drain into the operation response.
   */
  @Test
  public void multipleSignalsAccumulateAllResponseLinks() {
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

    List<Link> captured = nexusCtx.getResponseLinks();
    Assert.assertEquals(
        "expected one response link per signal call",
        Arrays.asList(firstResponseLink, secondResponseLink),
        captured);
  }

  /**
   * Happy-path mirror of {@link #signalForwardsInboundLinksAndCapturesResponseLink} but for {@code
   * signalWithStart}. The forward direction must attach inbound links to {@link
   * SignalWithStartWorkflowExecutionRequest#getLinksList}, and the backward direction must capture
   * {@code response.signal_link} via the same response link path. Different proto field name
   * ({@code signal_link} vs {@code link}) and different code path inside {@link
   * io.temporal.internal.client.RootWorkflowClientInvoker#signalWithStart} — a regression in only
   * one branch would otherwise pass the plain-signal tests.
   */
  @Test
  public void signalWithStartForwardsInboundLinksAndCapturesResponseLink() {
    Link inboundLink =
        workflowEventLink(
            "caller-wf", "caller-run", EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    nexusCtx.setRequestLinks(Collections.singletonList(inboundLink));

    Link responseLink =
        workflowEventLink(
            WORKFLOW_ID, "target-run", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    SignalWithStartWorkflowExecutionResponse response =
        SignalWithStartWorkflowExecutionResponse.newBuilder()
            .setRunId("target-run")
            .setSignalLink(responseLink)
            .build();
    when(genericClient.signalWithStart(any(SignalWithStartWorkflowExecutionRequest.class)))
        .thenReturn(response);

    invoker.signalWithStart(newSignalWithStartInput());

    // Forward direction: the SignalWithStartWorkflowExecutionRequest carries the inbound link.
    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).signalWithStart(captor.capture());
    SignalWithStartWorkflowExecutionRequest sent = captor.getValue();
    Assert.assertEquals("request should carry the single inbound link", 1, sent.getLinksCount());
    Assert.assertEquals(inboundLink, sent.getLinks(0));

    // Backward direction: response.signal_link is on the context for the task handler to read.
    List<Link> captured = nexusCtx.getResponseLinks();
    Assert.assertEquals("expected one captured response link", 1, captured.size());
    Assert.assertEquals(responseLink, captured.get(0));
  }

  /**
   * Mixed-RPC accumulation: a handler that issues one signal and one signalWithStart against the
   * same context must end up with both response links captured, in call order. Guards against
   * regressions where one of the two code paths stops appending to the same list.
   */
  @Test
  public void mixedSignalAndSignalWithStartAccumulateAllResponseLinks() {
    Link signalResponseLink =
        workflowEventLink("callee-s", "run-s", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    Link signalWithStartResponseLink =
        workflowEventLink(
            "callee-sws", "run-sws", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED);
    when(genericClient.signal(any(SignalWorkflowExecutionRequest.class)))
        .thenReturn(
            SignalWorkflowExecutionResponse.newBuilder().setLink(signalResponseLink).build());
    when(genericClient.signalWithStart(any(SignalWithStartWorkflowExecutionRequest.class)))
        .thenReturn(
            SignalWithStartWorkflowExecutionResponse.newBuilder()
                .setRunId("run-sws")
                .setSignalLink(signalWithStartResponseLink)
                .build());

    invoker.signal(newSignalInput());
    invoker.signalWithStart(newSignalWithStartInput());

    Assert.assertEquals(
        "expected one response link each from signal and signalWithStart, in call order",
        Arrays.asList(signalResponseLink, signalWithStartResponseLink),
        nexusCtx.getResponseLinks());
  }

  /**
   * Post-rebase start contract: a plain {@code start()} issued from inside a Nexus operation
   * handler captures only the FORWARD operation->workflow link (via {@code
   * setStartWorkflowResponseLink}) and deliberately does NOT add a response link (unlike
   * signal/signalWithStart). Replaces the two start-link tests removed by the rebase and guards
   * against a regression that re-adds a response link on the start path.
   */
  @Test
  public void startSetsForwardLinkOnlyAndCapturesNoResponseLink() {
    Link startResponseLink =
        workflowEventLink(
            WORKFLOW_ID, "target-run", EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED);
    StartWorkflowExecutionResponse response =
        StartWorkflowExecutionResponse.newBuilder()
            .setRunId("target-run")
            .setLink(startResponseLink)
            .build();
    when(genericClient.start(any(StartWorkflowExecutionRequest.class))).thenReturn(response);

    invoker.start(newStartInput());

    // Forward direction: the start response link is stashed for NexusStartWorkflowHelper to read.
    Assert.assertEquals(
        "expected the forward start link on the context",
        startResponseLink,
        nexusCtx.getStartWorkflowResponseLink());

    // Backward direction: start must not add a response link.
    Assert.assertTrue(
        "expected no response link captured on the start path",
        nexusCtx.getResponseLinks().isEmpty());
  }

  /**
   * Verify startUpdate only adds completion callback if {@link NexusOperationMetadata} is present
   * on the operation context, i.e. when the update was started via {@code
   * TemporalNexusClientImpl.startWorkflowUpdate}
   */
  @Test
  public void updateWorkflowSetCallbacksIffNexusMetadataPresent() {
    nexusCtx.setNexusOperationMetadata(
        new NexusOperationMetadata("rid", "temporal://dummy", Collections.emptyMap()));

    when(genericClient.update(any(UpdateWorkflowExecutionRequest.class), any()))
        .thenReturn(acceptedUpdateResponse());

    invoker.startUpdate(newStartUpdateInput());

    ArgumentCaptor<UpdateWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(UpdateWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).update(captor.capture(), any());
    Assert.assertEquals(
        "expect callback to be attached when NexusOperationMetadata is present",
        1,
        captor.getValue().getRequest().getCompletionCallbacksCount());
  }

  /**
   * Verify plain Nexus operation handlers that don't go through {@code TemporalNexusClient} ie,
   * missing {@link NexusOperationMetadata} do not have a callback attached
   */
  @Test
  public void updateWorkflowSkipSetCallbacksIfNexusMetadataAbsent() {
    when(genericClient.update(any(UpdateWorkflowExecutionRequest.class), any()))
        .thenReturn(acceptedUpdateResponse());

    invoker.startUpdate(newStartUpdateInput());

    ArgumentCaptor<UpdateWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(UpdateWorkflowExecutionRequest.class);
    org.mockito.Mockito.verify(genericClient).update(captor.capture(), any());
    Assert.assertEquals(
        "expect no callback when NexusOperationMetadata is absent",
        0,
        captor.getValue().getRequest().getCompletionCallbacksCount());
  }

  private static UpdateWorkflowExecutionResponse acceptedUpdateResponse() {
    return UpdateWorkflowExecutionResponse.newBuilder()
        .setStage(
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED)
        .setUpdateRef(
            UpdateRef.newBuilder()
                .setWorkflowExecution(
                    WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("rid"))
                .setUpdateId("uid"))
        .build();
  }

  private static StartUpdateInput<String> newStartUpdateInput() {
    return new StartUpdateInput<>(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("rid").build(),
        Optional.of("TestWorkflow"),
        "un",
        Header.empty(),
        "uid",
        new Object[] {" "},
        String.class,
        String.class,
        "",
        io.temporal.api.update.v1.WaitPolicy.newBuilder()
            .setLifecycleStage(WorkflowUpdateStage.ACCEPTED.getProto())
            .build());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────

  private static WorkflowSignalInput newSignalInput() {
    return new WorkflowSignalInput(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build(),
        "test-signal",
        Header.empty(),
        new Object[] {"payload"});
  }

  private static WorkflowStartInput newStartInput() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue("tq").setDisableEagerExecution(true).build();
    return new WorkflowStartInput(
        WORKFLOW_ID, "TestWorkflow", Header.empty(), new Object[] {}, options);
  }

  private static WorkflowSignalWithStartInput newSignalWithStartInput() {
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue("tq").build();
    WorkflowStartInput startInput =
        new WorkflowStartInput(
            WORKFLOW_ID, "TestWorkflow", Header.empty(), new Object[] {}, options);
    return new WorkflowSignalWithStartInput(
        startInput, "test-signal", new Object[] {"signal-payload"});
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
