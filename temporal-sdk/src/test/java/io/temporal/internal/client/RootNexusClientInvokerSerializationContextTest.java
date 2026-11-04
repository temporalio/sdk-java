package io.temporal.internal.client;

import static io.temporal.internal.nexus.ContextRecordingCodec.stamp;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionResponse;
import io.temporal.client.NexusClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.GetNexusOperationResultInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.payload.context.NexusSerializationContext;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests that a standalone Nexus operation's result is decoded with the context the server
 * returns, falling back to the start request's context for older servers.
 */
public class RootNexusClientInvokerSerializationContextTest {

  private static final NexusSerializationContext SERVER_CONTEXT =
      new NexusSerializationContext("server-endpoint", "server-service", "server-operation");
  private static final PropagatedSerializationContext PROPAGATED =
      PropagatedSerializationContext.newBuilder()
          .setEndpoint("server-endpoint")
          .setService("server-service")
          .setOperation("server-operation")
          .build();
  private static final NexusSerializationContext START_CONTEXT =
      new NexusSerializationContext("start-endpoint", "start-service", "start-operation");

  private ContextRecordingCodec codec;
  private GenericWorkflowClient genericClient;
  private RootNexusClientInvoker invoker;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    genericClient = mock(GenericWorkflowClient.class);
    invoker =
        new RootNexusClientInvoker(
            genericClient,
            new NexusClientResolvedOptions(
                NexusClientOptions.getDefaultInstance().getNamespace(),
                NexusClientOptions.getDefaultInstance().getInterceptors(),
                codec.dataConverter(),
                NexusClientOptions.getDefaultInstance().getIdentity()));
  }

  @Test
  public void serverContextTakesPrecedenceOverStartContext() throws Exception {
    stubClosedPoll(SERVER_CONTEXT, PROPAGATED);

    String result = invoker.getNexusOperationResult(startedHandleInput()).getResult();

    Assert.assertEquals("result", result);
    Assert.assertEquals(
        Collections.singletonList(ContextRecordingCodec.describe(SERVER_CONTEXT)),
        codec.decodeContexts());
  }

  @Test
  public void handleByIdUsesServerContext() throws Exception {
    stubClosedPoll(SERVER_CONTEXT, PROPAGATED);

    invoker.getNexusOperationResult(handleByIdInput());

    Assert.assertEquals(
        Collections.singletonList(ContextRecordingCodec.describe(SERVER_CONTEXT)),
        codec.decodeContexts());
  }

  @Test
  public void olderServerFallsBackToStartContext() throws Exception {
    stubClosedPoll(START_CONTEXT, null);

    invoker.getNexusOperationResult(startedHandleInput());

    Assert.assertEquals(
        Collections.singletonList(ContextRecordingCodec.describe(START_CONTEXT)),
        codec.decodeContexts());
  }

  private void stubClosedPoll(
      NexusSerializationContext encodedWith, PropagatedSerializationContext propagated) {
    when(genericClient.pollNexusOperationExecution(
            any(PollNexusOperationExecutionRequest.class), any(Deadline.class)))
        .thenReturn(closedResponse(encodedWith, propagated));
  }

  private static PollNexusOperationExecutionResponse closedResponse(
      NexusSerializationContext encodedWith, PropagatedSerializationContext propagated) {
    Payload payload =
        stamp(
            DefaultDataConverter.STANDARD_INSTANCE
                .toPayload("result")
                .orElseThrow(() -> new IllegalStateException("expected payload")),
            encodedWith);
    PollNexusOperationExecutionResponse.Builder response =
        PollNexusOperationExecutionResponse.newBuilder()
            .setWaitStage(NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED)
            .setResult(payload);
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static GetNexusOperationResultInput<String> startedHandleInput() {
    return new GetNexusOperationResultInput<>(
        "op-1",
        null,
        Deadline.after(10, TimeUnit.SECONDS),
        String.class,
        String.class,
        START_CONTEXT.getEndpoint(),
        START_CONTEXT.getService(),
        START_CONTEXT.getOperation());
  }

  private static GetNexusOperationResultInput<String> handleByIdInput() {
    return new GetNexusOperationResultInput<>(
        "op-1", null, Deadline.after(10, TimeUnit.SECONDS), String.class, String.class);
  }
}
