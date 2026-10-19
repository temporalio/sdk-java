package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.common.converter.*;
import io.temporal.internal.payload.visitor.SystemNexusPayloads;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.workflow.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Test;

public class SystemNexusDataConverterTest {
  private static final String SERVICE = "temporal.api.workflowservice.v1.WorkflowService";
  private static final String OPERATION = "SignalWithStartWorkflowExecution";

  private static SignalWithStartWorkflowRequest request(String id) {
    return SignalWithStartWorkflowRequest.newBuilder()
        .setNamespace("destination")
        .setId(id)
        .setWorkflow("Target")
        .setTaskQueue("queue")
        .setSignal("notify")
        .setArgs(Arrays.asList("workflow", 42))
        .setSignalArgs(Collections.singletonList("signal"))
        .setMemo(Collections.singletonMap("memo", "value"))
        .setHeaders(
            Collections.singletonMap(
                "trace", DefaultDataConverter.STANDARD_INSTANCE.toPayload("trace-value").get()))
        .setUserMetadata(UserMetadata.newBuilder().setStaticSummary("summary").build())
        .build();
  }

  @Test
  public void decodesUnmarkedServerResponse() {
    Payload response =
        SystemNexusPayloads.pack(
                io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse
                    .newBuilder()
                    .setRunId("run")
                    .setStarted(true)
                    .build())
            .toBuilder()
            .removeMetadata("__temporal_system_payload")
            .build();
    assertFalse(SystemNexusPayloads.isSystemPayload(response));
    SignalWithStartWorkflowResponse decoded =
        new SystemNexusDataConverter(DefaultDataConverter.STANDARD_INSTANCE)
            .fromPayload(
                response,
                SignalWithStartWorkflowResponse.class,
                SignalWithStartWorkflowResponse.class);
    assertEquals("run", decoded.getRunId());
    assertEquals(Boolean.TRUE, decoded.getStarted());
  }

  @Test
  public void nestedPayloadsUseDestinationContextAndOuterEnvelopeBypassesCodecs() throws Exception {
    SignalWithStartWorkflowRequest request = request("target");
    WorkflowSerializationContext context =
        (WorkflowSerializationContext)
            SystemNexusOperations.serializationContext(SERVICE, OPERATION, request);
    assertEquals("destination", context.getNamespace());
    assertEquals("target", context.getWorkflowId());
    DataConverter user =
        new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE,
                Collections.singletonList(new ContextCodec(null)))
            .withContext(context);
    SystemNexusDataConverter converter = new SystemNexusDataConverter(user);
    Payload encoded = converter.toPayload(request).get();
    assertTrue(SystemNexusPayloads.isSystemPayload(encoded));
    assertFalse(encoded.getMetadataMap().containsKey("test-context"));
    SignalWithStartWorkflowExecutionRequest wire =
        SignalWithStartWorkflowExecutionRequest.parseFrom(encoded.getData());
    assertEquals("", wire.getRequestId());
    assertEquals(
        "target", wire.getInput().getPayloads(0).getMetadataOrThrow("test-context").toStringUtf8());
    assertEquals(
        "target",
        wire.getSignalInput().getPayloads(0).getMetadataOrThrow("test-context").toStringUtf8());
    assertEquals(
        "target",
        wire.getMemo().getFieldsOrThrow("memo").getMetadataOrThrow("test-context").toStringUtf8());
    SignalWithStartWorkflowRequest decoded =
        converter.fromPayload(
            encoded, SignalWithStartWorkflowRequest.class, SignalWithStartWorkflowRequest.class);
    assertEquals(request.getArgs(), decoded.getArgs());
    assertEquals(request.getSignalArgs(), decoded.getSignalArgs());
    assertEquals(request.getMemo(), decoded.getMemo());
    assertEquals(request.getHeaders(), decoded.getHeaders());
    assertEquals(
        "target",
        wire.getHeader()
            .getFieldsOrThrow("trace")
            .getMetadataOrThrow("test-context")
            .toStringUtf8());
    assertEquals("summary", decoded.getUserMetadata().getStaticSummary());
  }

  @Test
  public void codecsVisitNestedPayloadsInsideRawSystemEnvelopes() {
    SignalWithStartWorkflowRequest request = request("target");
    CodecDataConverter user =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE,
            Collections.singletonList(new ContextCodec("target")));
    Payload plain =
        new SystemNexusDataConverter(DefaultDataConverter.STANDARD_INSTANCE)
            .toPayload(request)
            .get();
    Payload encoded = user.encode(Collections.singletonList(plain)).get(0);
    assertEquals(plain, user.decode(Collections.singletonList(encoded)).get(0));
  }

  @Test
  public void responseAndRawValuesRoundTripAndConverterScopesRestore() {
    SystemNexusDataConverter converter =
        new SystemNexusDataConverter(DefaultDataConverter.STANDARD_INSTANCE);
    SignalWithStartWorkflowResponse response =
        SignalWithStartWorkflowResponse.newBuilder().setRunId("run").setStarted(true).build();
    Payload encoded = converter.toPayload(response).get();
    assertEquals(
        "run",
        converter
            .fromPayload(
                encoded,
                SignalWithStartWorkflowResponse.class,
                SignalWithStartWorkflowResponse.class)
            .getRunId());
    assertSame(encoded, converter.toPayload(new RawValue(encoded)).get());
    DataConverter original = NexgenRuntime.dataConverter();
    assertThrows(
        IllegalStateException.class,
        () ->
            NexgenRuntime.withDataConverter(
                converter,
                () -> {
                  throw new IllegalStateException("test");
                }));
    assertSame(original, NexgenRuntime.dataConverter());
  }

  @Test
  public void operationsRetainIndependentDestinationConverters() {
    SignalWithStartWorkflowRequest first = request("first");
    SignalWithStartWorkflowRequest second = request("second");
    DataConverter base =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE,
            Collections.singletonList(new ContextCodec(null)));
    SystemNexusDataConverter firstConverter =
        new SystemNexusDataConverter(
            base.withContext(
                SystemNexusOperations.serializationContext(SERVICE, OPERATION, first)));
    SystemNexusDataConverter secondConverter =
        new SystemNexusDataConverter(
            base.withContext(
                SystemNexusOperations.serializationContext(SERVICE, OPERATION, second)));
    Payload firstPayload = firstConverter.toPayload(first).get();
    Payload secondPayload = secondConverter.toPayload(second).get();
    assertEquals(
        "second",
        secondConverter
            .fromPayload(
                secondPayload,
                SignalWithStartWorkflowRequest.class,
                SignalWithStartWorkflowRequest.class)
            .getId());
    assertEquals(
        "first",
        firstConverter
            .fromPayload(
                firstPayload,
                SignalWithStartWorkflowRequest.class,
                SignalWithStartWorkflowRequest.class)
            .getId());
    assertSame(DefaultDataConverter.STANDARD_INSTANCE, NexgenRuntime.dataConverter());
  }

  @Test
  public void failuresAndEncodedAttributesUseTheDestinationConverter() {
    DataConverter user =
        new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE,
                Collections.singletonList(new ContextCodec(null)),
                true)
            .withContext(new WorkflowSerializationContext("destination", "target"));
    SystemNexusDataConverter converter = new SystemNexusDataConverter(user);
    io.temporal.api.failure.v1.Failure failure =
        converter.exceptionToFailure(
            io.temporal.failure.ApplicationFailure.newFailure("message", "type", "details"));
    io.temporal.failure.ApplicationFailure decoded =
        (io.temporal.failure.ApplicationFailure) converter.failureToException(failure);
    assertEquals("message", decoded.getOriginalMessage());
    assertEquals("details", decoded.getDetails().get(0, String.class));
  }

  private static final class ContextCodec implements PayloadCodec {
    private final String id;

    private ContextCodec(String id) {
      this.id = id;
    }

    @Nonnull
    @Override
    public PayloadCodec withContext(@Nonnull SerializationContext context) {
      assertTrue(context instanceof WorkflowSerializationContext);
      return new ContextCodec(((WorkflowSerializationContext) context).getWorkflowId());
    }

    @Nonnull
    @Override
    public List<Payload> encode(@Nonnull List<Payload> values) {
      assertNotNull(id);
      return values.stream()
          .map(
              value -> {
                assertFalse(SystemNexusPayloads.isSystemPayload(value));
                assertFalse(
                    "Payload was encoded twice",
                    value.getMetadataMap().containsKey("test-context"));
                return value.toBuilder()
                    .putMetadata("test-context", ByteString.copyFromUtf8(id))
                    .build();
              })
          .collect(Collectors.toList());
    }

    @Nonnull
    @Override
    public List<Payload> decode(@Nonnull List<Payload> values) {
      return values.stream()
          .map(
              value -> {
                assertEquals(id, value.getMetadataOrThrow("test-context").toStringUtf8());
                return value.toBuilder().removeMetadata("test-context").build();
              })
          .collect(Collectors.toList());
    }
  }
}
