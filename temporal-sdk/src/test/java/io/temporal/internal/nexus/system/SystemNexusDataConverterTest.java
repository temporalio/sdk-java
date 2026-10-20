package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
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
    return request(id, DefaultDataConverter.STANDARD_INSTANCE);
  }

  private static SignalWithStartWorkflowRequest request(String id, DataConverter converter) {
    return SignalWithStartWorkflowRequest.newBuilder()
        .setNamespace("destination")
        .setId(id)
        .setWorkflow("Target")
        .setTaskQueue("queue")
        .setSignal("notify")
        .setArgs(converter.toPayloads("workflow", 42).get())
        .setSignalArgs(converter.toPayloads("signal").get())
        .setMemo(Memo.newBuilder().putFields("memo", converter.toPayload("value").get()).build())
        .setHeaders(
            Header.newBuilder()
                .putFields("trace", converter.toPayload("trace-value").get())
                .build())
        .setUserMetadata(
            UserMetadata.newBuilder()
                .setStaticSummary(converter.toPayload("summary").get())
                .setStaticDetails(converter.toPayload("details").get())
                .build())
        .setSearchAttributes(
            io.temporal.api.common.v1.SearchAttributes.newBuilder()
                .putIndexedFields(
                    "key",
                    Payload.newBuilder()
                        .putMetadata(
                            "encoding", ByteString.copyFromUtf8("unknown/search-attribute"))
                        .setData(ByteString.copyFromUtf8("opaque"))
                        .build())
                .build())
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
    WorkflowSerializationContext context =
        new WorkflowSerializationContext("destination", "target");
    DataConverter user =
        new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE,
                Collections.singletonList(new ContextCodec(null)))
            .withContext(context);
    SignalWithStartWorkflowRequest request = request("target", user);
    WorkflowSerializationContext acquired =
        (WorkflowSerializationContext)
            SystemNexusOperations.serializationContext(SERVICE, OPERATION, request);
    assertEquals("destination", acquired.getNamespace());
    assertEquals("target", acquired.getWorkflowId());
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
    assertEquals(request.getSearchAttributes(), decoded.getSearchAttributes());
    assertEquals(
        request.getUserMetadata().getStaticDetails(), decoded.getUserMetadata().getStaticDetails());
    assertEquals(
        "target",
        wire.getHeader()
            .getFieldsOrThrow("trace")
            .getMetadataOrThrow("test-context")
            .toStringUtf8());
    assertEquals(
        "summary",
        user.fromPayload(decoded.getUserMetadata().getStaticSummary(), String.class, String.class));
  }

  @Test
  public void transferConversionDoesNotUseTheUserConverter() throws Exception {
    SignalWithStartWorkflowRequest request = request("target");
    DataConverter user = mock(DataConverter.class);
    SystemNexusDataConverter converter = new SystemNexusDataConverter(user);
    Payload encoded = converter.toPayload(request).get();
    assertTrue(SystemNexusPayloads.isSystemPayload(encoded));
    SignalWithStartWorkflowExecutionRequest wire =
        SignalWithStartWorkflowExecutionRequest.parseFrom(encoded.getData());
    assertEquals("target", wire.getWorkflowId());
    SignalWithStartWorkflowRequest decoded =
        converter.fromPayload(
            encoded, SignalWithStartWorkflowRequest.class, SignalWithStartWorkflowRequest.class);
    assertEquals(request.getArgs(), decoded.getArgs());
    assertEquals(request.getSignalArgs(), decoded.getSignalArgs());
    assertEquals(request.getMemo(), decoded.getMemo());
    assertEquals(request.getHeaders(), decoded.getHeaders());
    verifyNoInteractions(user);
  }

  @Test
  public void responseAndRawValuesRoundTrip() {
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
  }

  @Test
  public void transferConversionRequiresNoConverterContextOrTypeHints() {
    SignalWithStartWorkflowRequest.Converter converter =
        new SignalWithStartWorkflowRequest.Converter();
    SignalWithStartWorkflowRequest request = request("target");
    SignalWithStartWorkflowRequest decoded =
        converter.fromTransferType(
            converter.toTransferType(request), SignalWithStartWorkflowRequest.class);
    assertEquals(request.getArgs(), decoded.getArgs());
    assertEquals(request.getMemo(), decoded.getMemo());
  }

  @Test
  public void requestsRetainIndependentlySerializedDestinationPayloads() {
    DataConverter base =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE,
            Collections.singletonList(new ContextCodec(null)));
    SignalWithStartWorkflowRequest first =
        request(
            "first", base.withContext(new WorkflowSerializationContext("destination", "first")));
    SignalWithStartWorkflowRequest second =
        request(
            "second", base.withContext(new WorkflowSerializationContext("destination", "second")));
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
        "first", first.getArgs().getPayloads(0).getMetadataOrThrow("test-context").toStringUtf8());
    assertEquals(
        "second",
        second.getArgs().getPayloads(0).getMetadataOrThrow("test-context").toStringUtf8());
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
  }

  @Test
  public void packedSequencesStayOpaqueUntilTheCallerDecodesWithKnownTypes() {
    DataConverter user =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(new PackingCodec()));
    SignalWithStartWorkflowRequest request = request("target", user);
    assertEquals(1, request.getArgs().getPayloadsCount());
    SystemNexusDataConverter converter = new SystemNexusDataConverter(mock(DataConverter.class));
    Payload envelope = converter.toPayload(request).get();
    SignalWithStartWorkflowRequest decoded =
        converter.fromPayload(
            envelope, SignalWithStartWorkflowRequest.class, SignalWithStartWorkflowRequest.class);
    assertEquals(request.getArgs(), decoded.getArgs());
    assertEquals(request.getSignalArgs(), decoded.getSignalArgs());
    assertEquals(request.getMemo(), decoded.getMemo());
    assertEquals(
        request.getUserMetadata().getStaticSummary(), decoded.getUserMetadata().getStaticSummary());
    assertArrayEquals(
        new Object[] {"workflow", 42},
        user.fromPayloads(
            Optional.of(decoded.getArgs()),
            new Class<?>[] {String.class, Integer.class},
            new java.lang.reflect.Type[] {String.class, Integer.class}));
  }

  private static final class PackingCodec implements PayloadCodec {
    @Nonnull
    @Override
    public List<Payload> encode(@Nonnull List<Payload> values) {
      return Collections.singletonList(
          Payload.newBuilder()
              .putMetadata("encoding", ByteString.copyFromUtf8("test/packed"))
              .setData(Payloads.newBuilder().addAllPayloads(values).build().toByteString())
              .build());
    }

    @Nonnull
    @Override
    public List<Payload> decode(@Nonnull List<Payload> values) {
      List<Payload> decoded = new ArrayList<>();
      for (Payload value : values) {
        assertEquals("test/packed", value.getMetadataOrThrow("encoding").toStringUtf8());
        try {
          decoded.addAll(Payloads.parseFrom(value.getData()).getPayloadsList());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          throw new AssertionError(e);
        }
      }
      return decoded;
    }
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
