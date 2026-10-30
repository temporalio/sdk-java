package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.ClientDataConverterFactory;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.codec.ZlibPayloadCodec;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.Test;

public class HeaderUtilsTest {
  @Test
  public void producerOptInPreservesWireReadyHeadersAndPrivateViews() {
    Payload serialized =
        DefaultDataConverter.STANDARD_INSTANCE
            .toPayload(String.join("", Collections.nCopies(100, "context")))
            .get();
    PayloadCodec codec = new ZlibPayloadCodec();
    Payload encoded = codec.encode(Collections.singletonList(serialized)).get(0);
    assertEquals(ByteString.copyFromUtf8("binary/zlib"), encoded.getMetadataOrThrow("encoding"));
    ContextPropagator optedIn = mock(ContextPropagator.class);
    when(optedIn.encodeHeaders()).thenReturn(true);
    when(optedIn.serializeContext(any()))
        .thenReturn(Collections.singletonMap("context", serialized));
    ContextPropagator legacy = mock(ContextPropagator.class);
    when(legacy.serializeContext(any())).thenReturn(Collections.singletonMap("legacy", encoded));
    Header header = HeaderUtils.fromContextPropagators(Arrays.asList(optedIn, legacy));
    header.getValues().put("raw", encoded);
    header.getValues().put("plaintext", serialized);
    io.temporal.api.common.v1.Header wire = HeaderUtils.toHeaderGrpc(header, null, codec);
    assertEquals(encoded, wire.getFieldsOrThrow("context"));
    assertEquals(encoded, wire.getFieldsOrThrow("legacy"));
    assertEquals(encoded, wire.getFieldsOrThrow("raw"));
    assertEquals(serialized, wire.getFieldsOrThrow("plaintext"));
    Header received = new Header(wire, codec);
    Map<String, Payload> view = received.getCodecDecodedValues();
    assertEquals(serialized, view.get("context"));
    assertEquals(serialized, view.get("plaintext"));
    view.clear();
    assertEquals(serialized, received.getCodecDecodedValues().get("context"));
    assertEquals(wire.getFieldsMap(), received.getValues());
    assertEquals(serialized, header.getValues().get("context"));

    header.getValues().clear();
    header.getValues().put("context", serialized);
    assertEquals(
        encoded, HeaderUtils.toHeaderGrpc(header, null, codec).getFieldsOrThrow("context"));
    header.putAll(Collections.singletonMap("context", serialized), false);
    assertEquals(
        serialized, HeaderUtils.toHeaderGrpc(header, null, codec).getFieldsOrThrow("context"));
    header.putAll(Collections.singletonMap("context", serialized), true);
    header.getValues().put("context", encoded);
    assertEquals(
        encoded, HeaderUtils.toHeaderGrpc(header, null, codec).getFieldsOrThrow("context"));
  }

  @Test
  public void codecsProcessSerializedHeadersOnceWithoutMutatingSources() {
    Map<String, Payload> values = new HashMap<>();
    Payload serialized = DefaultDataConverter.STANDARD_INSTANCE.toPayload("context").get();
    values.put("_tracer-data", serialized);
    values.put("__temporal_custom", serialized);
    Header header = Header.empty();
    header.putAll(values, true);
    Header overrides = Header.empty();
    overrides.putAll(Collections.singletonMap("_tracer-data", serialized), true);
    CountingCodec codec = new CountingCodec();
    DataConverter valuesConverter = mock(DataConverter.class);
    CodecDataConverter configured =
        new CodecDataConverter(valuesConverter, Collections.singletonList(codec));
    DataConverter enabled =
        new ExternalStorageDataConverter(ClientDataConverterFactory.forClient(configured), null)
            .withContext(new WorkflowSerializationContext("namespace", "workflow"));
    PayloadCodec enabledCodec = HeaderUtils.resolveHeaderCodec(enabled);

    io.temporal.api.common.v1.Header plain = HeaderUtils.toHeaderGrpc(header, overrides);
    assertEquals(plain, HeaderUtils.toHeaderGrpc(header, overrides, null));
    assertSame(plain, HeaderUtils.decode(plain, null));
    for (int i = 0; i < 2; i++) {
      io.temporal.api.common.v1.Header encoded =
          HeaderUtils.toHeaderGrpc(header, overrides, enabledCodec);
      assertEquals(plain, HeaderUtils.decode(encoded, enabledCodec));
    }
    assertEquals(4, codec.encoded);
    assertEquals(4, codec.decoded);
    assertEquals(serialized, header.getValues().get("_tracer-data"));
    assertEquals(serialized, overrides.getValues().get("_tracer-data"));
    verifyNoInteractions(valuesConverter);
  }

  @Test(expected = PayloadCodecException.class)
  public void codecFailuresAreNotRetriedAsPlaintext() {
    PayloadCodec codec = mock(PayloadCodec.class);
    when(codec.decode(any())).thenThrow(new PayloadCodecException("invalid encoded header"));
    HeaderUtils.decode(
        io.temporal.api.common.v1.Header.newBuilder()
            .putFields("key", Payload.getDefaultInstance())
            .build(),
        codec);
  }

  @Test
  public void contextualizationIsOptInAndRejectsNull() {
    PayloadCodec codec = mock(PayloadCodec.class);
    WorkflowSerializationContext context =
        new WorkflowSerializationContext("namespace", "workflow");
    Header legacy = new Header(Collections.singletonMap("raw", Payload.getDefaultInstance()));
    assertEquals(
        HeaderUtils.toHeaderGrpc(legacy, null),
        HeaderUtils.toHeaderGrpc(legacy, null, HeaderUtils.withContext(codec, context)));
    verifyNoInteractions(codec);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HeaderUtils.encode(
                HeaderUtils.toHeaderGrpc(legacy, null), HeaderUtils.withContext(codec, context)));
    when(codec.withContext(any())).thenReturn(new ChainCodec(Collections.emptyList()));
    assertEquals(
        HeaderUtils.toHeaderGrpc(legacy, null),
        HeaderUtils.encode(
            HeaderUtils.toHeaderGrpc(legacy, null), HeaderUtils.withContext(codec, context)));
  }

  @Test(expected = PayloadCodecException.class)
  public void rejectsCodecsThatChangeHeaderCardinality() {
    Header header = Header.empty();
    header.putAll(Collections.singletonMap("key", Payload.getDefaultInstance()), true);
    HeaderUtils.toHeaderGrpc(
        header,
        null,
        new CountingCodec() {
          @Override
          public List<Payload> encode(@Nonnull List<Payload> payloads) {
            return Collections.emptyList();
          }
        });
  }

  private static class CountingCodec implements PayloadCodec {
    private final PayloadCodec delegate = new PrefixPayloadCodec();
    private int encoded;
    private int decoded;

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      encoded++;
      return delegate.encode(payloads);
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      decoded++;
      return delegate.decode(payloads);
    }
  }
}
