package io.temporal.internal.client;

import static io.temporal.internal.nexus.ContextRecordingCodec.contextOf;
import static io.temporal.internal.nexus.ContextRecordingCodec.stamp;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionContinuedAsNewEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionFailedEventAttributes;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.update.v1.Outcome;
import io.temporal.api.update.v1.Request;
import io.temporal.api.update.v1.UpdateRef;
import io.temporal.api.update.v1.WaitPolicy;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.PollWorkflowExecutionUpdateRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowExecutionUpdateResponse;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.GetResultInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.PollWorkflowUpdateInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.StartUpdateInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for propagating the Nexus serialization context to workflows started, and updates
 * sent, by a Nexus operation handler, and for decoding their results with the context the server
 * returns.
 */
public class RootWorkflowClientInvokerNexusSerializationContextTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String WORKFLOW_ID = "wf-target";
  private static final NexusSerializationContext NEXUS_CONTEXT =
      new NexusSerializationContext("endpoint", "service", "operation");
  private static final PropagatedSerializationContext PROPAGATED =
      PropagatedSerializationContext.newBuilder()
          .setEndpoint("endpoint")
          .setService("service")
          .setOperation("operation")
          .build();
  private static final String NEXUS = ContextRecordingCodec.describe(NEXUS_CONTEXT);
  private static final String WORKFLOW =
      ContextRecordingCodec.describe(new WorkflowSerializationContext(NAMESPACE, WORKFLOW_ID));

  private ContextRecordingCodec codec;
  private GenericWorkflowClient genericClient;
  private RootWorkflowClientInvoker invoker;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    genericClient = mock(GenericWorkflowClient.class);
    invoker =
        new RootWorkflowClientInvoker(
            genericClient,
            NAMESPACE,
            "test-identity",
            QueryRejectCondition.QUERY_REJECT_CONDITION_NONE,
            Collections.emptyList(),
            codec.dataConverter(),
            new WorkerFactoryRegistry());
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  @Test
  public void startFromNexusHandlerPropagatesContextButKeepsWorkflowContextForPayloads() {
    enterNexusHandler(NEXUS_CONTEXT);
    when(genericClient.start(any()))
        .thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());

    invoker.start(newStartInput());

    StartWorkflowExecutionRequest sent = captureStartRequest();
    Assert.assertEquals(PROPAGATED, sent.getPropagatedNexusSerializationContext());
    // Only the workflow's result and failure, which are returned to the Nexus caller, use the
    // propagated context.
    Assert.assertEquals(WORKFLOW, contextOf(sent.getInput().getPayloads(0)));
    Assert.assertEquals(WORKFLOW, contextOf(sent.getMemo().getFieldsOrThrow("memo-key")));
    Assert.assertEquals(WORKFLOW, contextOf(sent.getUserMetadata().getSummary()));
    Assert.assertEquals(WORKFLOW, contextOf(sent.getUserMetadata().getDetails()));
  }

  @Test
  public void startOutsideNexusHandlerDoesNotPropagateContext() {
    when(genericClient.start(any()))
        .thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());

    invoker.start(newStartInput());

    StartWorkflowExecutionRequest sent = captureStartRequest();
    Assert.assertFalse(sent.hasPropagatedNexusSerializationContext());
    Assert.assertEquals(WORKFLOW, contextOf(sent.getInput().getPayloads(0)));
  }

  @Test
  public void signalWithStartFromNexusHandlerKeepsWorkflowContext() {
    enterNexusHandler(NEXUS_CONTEXT);
    when(genericClient.signalWithStart(any()))
        .thenReturn(SignalWithStartWorkflowExecutionResponse.getDefaultInstance());

    invoker.signalWithStart(
        new WorkflowSignalWithStartInput(newStartInput(), "signal", new Object[] {"signal-arg"}));

    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);
    verify(genericClient).signalWithStart(captor.capture());
    Assert.assertEquals(WORKFLOW, contextOf(captor.getValue().getInput().getPayloads(0)));
    Assert.assertEquals(WORKFLOW, contextOf(captor.getValue().getSignalInput().getPayloads(0)));
  }

  @Test
  public void getResultDecodesWithPropagatedContext() throws Exception {
    when(genericClient.longPollHistory(any(GetWorkflowExecutionHistoryRequest.class), any()))
        .thenReturn(historyResponse(completedEvent(NEXUS_CONTEXT), PROPAGATED));

    String result = invoker.getResult(newGetResultInput()).getResult();

    Assert.assertEquals("result", result);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void getResultWithoutPropagatedContextUsesWorkflowContext() throws Exception {
    when(genericClient.longPollHistory(any(GetWorkflowExecutionHistoryRequest.class), any()))
        .thenReturn(historyResponse(completedEvent(null), null));

    invoker.getResult(newGetResultInput());

    Assert.assertEquals(Collections.singletonList(WORKFLOW), codec.decodeContexts());
  }

  @Test
  public void getResultDecodesFailureWithPropagatedContext() {
    when(genericClient.longPollHistory(any(GetWorkflowExecutionHistoryRequest.class), any()))
        .thenReturn(historyResponse(failedEvent(NEXUS_CONTEXT), PROPAGATED));

    Assert.assertThrows(
        WorkflowFailedException.class, () -> invoker.getResult(newGetResultInput()));
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void getResultUsesContextOfTheRunThatClosed() throws Exception {
    // The context is per run, so the one returned for a run that continued as new must not be
    // applied to the next run. The server does carry the context into the continued run (see
    // NexusSerializationContextPropagationTest); the next run here returns none so that a context
    // leaked from the first run would be detected.
    HistoryEvent continuedAsNew =
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW)
            .setWorkflowExecutionContinuedAsNewEventAttributes(
                WorkflowExecutionContinuedAsNewEventAttributes.newBuilder()
                    .setNewExecutionRunId("run-2"))
            .build();
    when(genericClient.longPollHistory(any(GetWorkflowExecutionHistoryRequest.class), any()))
        .thenReturn(historyResponse(continuedAsNew, PROPAGATED))
        .thenReturn(historyResponse(completedEvent(null), null));

    invoker.getResult(newGetResultInput());

    Assert.assertEquals(Collections.singletonList(WORKFLOW), codec.decodeContexts());
  }

  @Test
  public void getResultAsyncKeepsContextAcrossEmptyPolls() throws Exception {
    // The context arrives on an empty long poll; the close event comes on the next one.
    GetWorkflowExecutionHistoryResponse emptyPoll =
        GetWorkflowExecutionHistoryResponse.newBuilder()
            .setPropagatedNexusSerializationContext(PROPAGATED)
            .build();
    when(genericClient.longPollHistoryAsync(
            any(GetWorkflowExecutionHistoryRequest.class), any(Deadline.class)))
        .thenReturn(CompletableFuture.completedFuture(emptyPoll))
        .thenReturn(
            CompletableFuture.completedFuture(
                historyResponse(completedEvent(NEXUS_CONTEXT), null)));

    invoker.getResultAsync(newGetResultInput()).getResult().get();

    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void updateFromNexusHandlerPropagatesContextButKeepsWorkflowContextForArguments()
      throws Exception {
    enterNexusHandler(NEXUS_CONTEXT);
    when(genericClient.update(any(UpdateWorkflowExecutionRequest.class), any(Deadline.class)))
        .thenReturn(completedUpdate(successOutcome(NEXUS_CONTEXT), PROPAGATED));

    String result = invoker.startUpdate(newStartUpdateInput()).getResult();

    ArgumentCaptor<UpdateWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(UpdateWorkflowExecutionRequest.class);
    verify(genericClient).update(captor.capture(), any(Deadline.class));
    Request request = captor.getValue().getRequest();
    Assert.assertEquals(PROPAGATED, request.getPropagatedNexusSerializationContext());
    // The update's arguments are not returned to the Nexus caller, so they keep the workflow
    // context.
    Assert.assertEquals(WORKFLOW, contextOf(request.getInput().getArgs().getPayloads(0)));
    Assert.assertEquals("result", result);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void updateOutsideNexusHandlerDoesNotPropagateContext() throws Exception {
    when(genericClient.update(any(UpdateWorkflowExecutionRequest.class), any(Deadline.class)))
        .thenReturn(completedUpdate(successOutcome(null), null));

    invoker.startUpdate(newStartUpdateInput()).getResult();

    ArgumentCaptor<UpdateWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(UpdateWorkflowExecutionRequest.class);
    verify(genericClient).update(captor.capture(), any(Deadline.class));
    Assert.assertFalse(captor.getValue().getRequest().hasPropagatedNexusSerializationContext());
    Assert.assertEquals(Collections.singletonList(WORKFLOW), codec.decodeContexts());
  }

  @Test
  public void completedUpdateDecodesFailureWithPropagatedContext() {
    when(genericClient.update(any(UpdateWorkflowExecutionRequest.class), any(Deadline.class)))
        .thenReturn(completedUpdate(failureOutcome(NEXUS_CONTEXT), PROPAGATED));

    WorkflowUpdateHandle<String> handle = invoker.startUpdate(newStartUpdateInput());

    Assert.assertThrows(WorkflowUpdateException.class, handle::getResult);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void pollUpdateDecodesWithPropagatedContext() throws Exception {
    when(genericClient.pollUpdateAsync(
            any(PollWorkflowExecutionUpdateRequest.class), any(Deadline.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                polledUpdate(successOutcome(NEXUS_CONTEXT), PROPAGATED)));

    String result = invoker.pollWorkflowUpdate(newPollUpdateInput()).getResult().get();

    Assert.assertEquals("result", result);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void pollUpdateWithoutPropagatedContextUsesWorkflowContext() throws Exception {
    when(genericClient.pollUpdateAsync(
            any(PollWorkflowExecutionUpdateRequest.class), any(Deadline.class)))
        .thenReturn(CompletableFuture.completedFuture(polledUpdate(successOutcome(null), null)));

    invoker.pollWorkflowUpdate(newPollUpdateInput()).getResult().get();

    Assert.assertEquals(Collections.singletonList(WORKFLOW), codec.decodeContexts());
  }

  @Test
  public void pollUpdateDecodesFailureWithPropagatedContext() {
    when(genericClient.pollUpdateAsync(
            any(PollWorkflowExecutionUpdateRequest.class), any(Deadline.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                polledUpdate(failureOutcome(NEXUS_CONTEXT), PROPAGATED)));

    CompletableFuture<String> result = invoker.pollWorkflowUpdate(newPollUpdateInput()).getResult();

    CompletionException e = Assert.assertThrows(CompletionException.class, result::join);
    Assert.assertTrue(e.getCause() instanceof WorkflowUpdateException);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  private void enterNexusHandler(NexusSerializationContext serializationContext) {
    InternalNexusOperationContext nexusContext =
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", new NoopScope(), mock(WorkflowClient.class));
    if (serializationContext != null) {
      nexusContext.setSerializationContext(serializationContext);
    }
    CurrentNexusOperationContext.set(nexusContext);
  }

  private StartWorkflowExecutionRequest captureStartRequest() {
    ArgumentCaptor<StartWorkflowExecutionRequest> captor =
        ArgumentCaptor.forClass(StartWorkflowExecutionRequest.class);
    verify(genericClient).start(captor.capture());
    return captor.getValue();
  }

  private static WorkflowStartInput newStartInput() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue("tq")
            .setDisableEagerExecution(true)
            .setMemo(Collections.singletonMap("memo-key", "memo-value"))
            .setStaticSummary("summary")
            .setStaticDetails("details")
            .build();
    return new WorkflowStartInput(
        WORKFLOW_ID, "TestWorkflow", Header.empty(), new Object[] {"input"}, options);
  }

  private static GetResultInput<String> newGetResultInput() {
    return new GetResultInput<>(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("run-1").build(),
        Optional.of("TestWorkflow"),
        10,
        TimeUnit.SECONDS,
        String.class,
        String.class);
  }

  private static GetWorkflowExecutionHistoryResponse historyResponse(
      HistoryEvent event, PropagatedSerializationContext propagated) {
    GetWorkflowExecutionHistoryResponse.Builder response =
        GetWorkflowExecutionHistoryResponse.newBuilder()
            .setHistory(History.newBuilder().addEvents(event));
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static HistoryEvent completedEvent(NexusSerializationContext encodedWith) {
    return HistoryEvent.newBuilder()
        .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED)
        .setWorkflowExecutionCompletedEventAttributes(
            WorkflowExecutionCompletedEventAttributes.newBuilder()
                .setResult(payloads("result", encodedWith)))
        .build();
  }

  private static HistoryEvent failedEvent(NexusSerializationContext encodedWith) {
    Failure failure =
        Failure.newBuilder()
            .setMessage("failed")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder()
                    .setType("TestFailure")
                    .setDetails(payloads("detail", encodedWith)))
            .build();
    return HistoryEvent.newBuilder()
        .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED)
        .setWorkflowExecutionFailedEventAttributes(
            WorkflowExecutionFailedEventAttributes.newBuilder().setFailure(failure))
        .build();
  }

  private static Payloads payloads(String value, NexusSerializationContext encodedWith) {
    Payload payload =
        DefaultDataConverter.STANDARD_INSTANCE
            .toPayload(value)
            .orElseThrow(() -> new IllegalStateException("expected payload"));
    return Payloads.newBuilder()
        .addPayloads(
            encodedWith == null
                ? stamp(payload, new WorkflowSerializationContext(NAMESPACE, WORKFLOW_ID))
                : stamp(payload, encodedWith))
        .build();
  }

  private static StartUpdateInput<String> newStartUpdateInput() {
    return new StartUpdateInput<>(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build(),
        Optional.of("TestWorkflow"),
        "update",
        Header.empty(),
        "update-id",
        new Object[] {"input"},
        String.class,
        String.class,
        "",
        WaitPolicy.newBuilder()
            .setLifecycleStage(
                UpdateWorkflowExecutionLifecycleStage
                    .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)
            .build());
  }

  private static PollWorkflowUpdateInput<String> newPollUpdateInput() {
    return new PollWorkflowUpdateInput<>(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("run-1").build(),
        "update",
        "update-id",
        String.class,
        String.class,
        10,
        TimeUnit.SECONDS);
  }

  private static UpdateWorkflowExecutionResponse completedUpdate(
      Outcome outcome, PropagatedSerializationContext propagated) {
    UpdateWorkflowExecutionResponse.Builder response =
        UpdateWorkflowExecutionResponse.newBuilder()
            .setUpdateRef(updateRef())
            .setStage(
                UpdateWorkflowExecutionLifecycleStage
                    .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)
            .setOutcome(outcome);
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static PollWorkflowExecutionUpdateResponse polledUpdate(
      Outcome outcome, PropagatedSerializationContext propagated) {
    PollWorkflowExecutionUpdateResponse.Builder response =
        PollWorkflowExecutionUpdateResponse.newBuilder()
            .setUpdateRef(updateRef())
            .setStage(
                UpdateWorkflowExecutionLifecycleStage
                    .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)
            .setOutcome(outcome);
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static UpdateRef updateRef() {
    return UpdateRef.newBuilder()
        .setWorkflowExecution(
            WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("run-1"))
        .setUpdateId("update-id")
        .build();
  }

  private static Outcome successOutcome(NexusSerializationContext encodedWith) {
    return Outcome.newBuilder().setSuccess(payloads("result", encodedWith)).build();
  }

  private static Outcome failureOutcome(NexusSerializationContext encodedWith) {
    return Outcome.newBuilder()
        .setFailure(
            Failure.newBuilder()
                .setMessage("failed")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo.newBuilder()
                        .setType("TestFailure")
                        .setDetails(payloads("detail", encodedWith))))
        .build();
  }
}
