package io.temporal.internal.nexus.system;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.common.converter.*;
import io.temporal.internal.payload.visitor.SystemNexusPayloads;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class SystemNexusDataConverterTest {
  @Test
  public void scopesUserConversionAndKeepsTheOuterEnvelopeReadable() throws Exception {
    DataConverter user =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(new TaggingCodec()));
    SystemNexusDataConverter converter =
        new SystemNexusDataConverter(
            user.withContext(new WorkflowSerializationContext("destination", "target")));
    Payload encoded = converter.toPayload(new Envelope("value")).get();
    assertTrue(SystemNexusPayloads.isSystemPayload(encoded));
    assertFalse(encoded.containsMetadata("test-codec"));
    SignalWithStartWorkflowExecutionRequest wire =
        SignalWithStartWorkflowExecutionRequest.parseFrom(encoded.getData());
    assertTrue(wire.getInput().getPayloads(0).containsMetadata("test-codec"));
    assertEquals(
        "destination/target",
        wire.getInput().getPayloads(0).getMetadataOrThrow("test-codec").toStringUtf8());
    assertEquals("value", converter.fromPayload(encoded, Envelope.class, Envelope.class).value);
    assertThrows(IllegalStateException.class, SystemNexusDataConverter::currentDataConverter);
  }

  @Test
  public void acceptsUnmarkedServerResponsesAndRawValues() {
    SystemNexusDataConverter converter =
        new SystemNexusDataConverter(DefaultDataConverter.STANDARD_INSTANCE);
    Payload response =
        SystemNexusPayloads.pack(
                SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("run").build())
            .toBuilder()
            .removeMetadata("__temporal_system_payload")
            .build();
    assertEquals(
        "run",
        converter
            .fromPayload(
                response,
                SignalWithStartWorkflowExecutionResponse.class,
                SignalWithStartWorkflowExecutionResponse.class)
            .getRunId());
    assertSame(response, converter.toPayload(new RawValue(response)).get());
    assertSame(
        response, converter.fromPayload(response, RawValue.class, RawValue.class).getPayload());
    assertThrows(
        DataConverterException.class,
        () -> converter.fromPayload(response, Envelope.class, Envelope.class));
    assertThrows(DataConverterException.class, () -> converter.toPayload("not a transfer model"));
    assertThrows(IllegalStateException.class, SystemNexusDataConverter::currentDataConverter);
  }

  @Test
  public void restoresNestedScopesAfterSuccessAndFailure() {
    DataConverter outer = DefaultDataConverter.STANDARD_INSTANCE;
    DataConverter inner =
        new CodecDataConverter(outer, Collections.singletonList(new TaggingCodec()));
    SystemNexusDataConverter.withDataConverter(
        outer,
        () -> {
          assertSame(outer, SystemNexusDataConverter.currentDataConverter());
          assertSame(
              inner,
              SystemNexusDataConverter.withDataConverter(
                  inner, SystemNexusDataConverter::currentDataConverter));
          assertSame(outer, SystemNexusDataConverter.currentDataConverter());
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  SystemNexusDataConverter.withDataConverter(
                      inner,
                      () -> {
                        throw new IllegalArgumentException("failure");
                      }));
          assertSame(outer, SystemNexusDataConverter.currentDataConverter());
          return null;
        });
    assertThrows(IllegalStateException.class, SystemNexusDataConverter::currentDataConverter);
  }

  // A handwritten transfer fixture, independent of nexgen or workflow-facing generated bindings.
  @TransferTypeConvertible(Envelope.Converter.class)
  public static final class Envelope {
    final String value;

    Envelope(String value) {
      this.value = value;
    }

    public static final class Converter implements TransferTypeConverter<Envelope> {
      @Override
      public Type getTransferType(Type valueType) {
        return SignalWithStartWorkflowExecutionRequest.class;
      }

      @Override
      public Object toTransferType(Envelope value) {
        return SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setInput(SystemNexusDataConverter.currentDataConverter().toPayloads(value.value).get())
            .build();
      }

      @Override
      public Envelope fromTransferType(Object value, Type valueType) {
        SignalWithStartWorkflowExecutionRequest wire =
            (SignalWithStartWorkflowExecutionRequest) value;
        return new Envelope(
            SystemNexusDataConverter.currentDataConverter()
                .fromPayload(wire.getInput().getPayloads(0), String.class, String.class));
      }
    }
  }

  private static final class TaggingCodec implements PayloadCodec {
    private final String destination;

    TaggingCodec() {
      this("unscoped");
    }

    private TaggingCodec(String destination) {
      this.destination = destination;
    }

    @Override
    public PayloadCodec withContext(SerializationContext context) {
      WorkflowSerializationContext workflow = (WorkflowSerializationContext) context;
      return new TaggingCodec(workflow.getNamespace() + "/" + workflow.getWorkflowId());
    }

    @Override
    public List<Payload> encode(List<Payload> payloads) {
      return payloads.stream()
          .map(
              payload -> {
                assertFalse(SystemNexusPayloads.isSystemPayload(payload));
                return payload.toBuilder()
                    .putMetadata("test-codec", ByteString.copyFromUtf8(destination))
                    .build();
              })
          .collect(Collectors.toList());
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
      return payloads.stream()
          .map(
              payload -> {
                assertTrue(payload.containsMetadata("test-codec"));
                assertEquals(destination, payload.getMetadataOrThrow("test-codec").toStringUtf8());
                return payload.toBuilder().removeMetadata("test-codec").build();
              })
          .collect(Collectors.toList());
    }
  }
}
