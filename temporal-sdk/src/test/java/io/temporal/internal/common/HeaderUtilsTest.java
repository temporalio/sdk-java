package io.temporal.internal.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterWithCodec;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.HeaderEncodingDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.ClientDataConverterFactory;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
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
  public void codecsProcessSerializedHeadersOnceWithoutMutatingSources() {
    Map<String, Payload> values = new HashMap<>();
    Payload serialized = DefaultDataConverter.STANDARD_INSTANCE.toPayload("context").get();
    values.put("_tracer-data", serialized);
    values.put("__temporal_custom", serialized);
    Header header = new Header(values);
    Header overrides = new Header(Collections.singletonMap("_tracer-data", serialized));
    CountingCodec codec = new CountingCodec();
    DataConverter valuesConverter = mock(DataConverter.class);
    CodecDataConverter configured =
        new CodecDataConverter(valuesConverter, Collections.singletonList(codec));
    DataConverter enabled =
        new ExternalStorageDataConverter(
                ClientDataConverterFactory.forClient(new HeaderEncodingDataConverter(configured)),
                null)
            .withContext(new WorkflowSerializationContext("namespace", "workflow"));
    PayloadCodec disabledCodec = HeaderUtils.resolveHeaderCodec(configured);
    PayloadCodec enabledCodec = HeaderUtils.resolveHeaderCodec(enabled);

    io.temporal.api.common.v1.Header plain = HeaderUtils.toHeaderGrpc(header, overrides);
    assertEquals(plain, HeaderUtils.toHeaderGrpc(header, overrides, disabledCodec));
    assertSame(plain, HeaderUtils.decode(plain, disabledCodec));
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
  public void incompatibleLegacyHeadersDoNotFallBackToPlaintext() {
    HeaderUtils.decode(
        HeaderUtils.toHeaderGrpc(
            new Header(
                Collections.singletonMap(
                    "legacy", DefaultDataConverter.STANDARD_INSTANCE.toPayload("context").get())),
            null),
        new PrefixPayloadCodec());
  }

  @Test
  public void contextualizationCannotSilentlyDisableTheCodec() {
    PayloadCodec codec = mock(PayloadCodec.class);
    WorkflowSerializationContext context =
        new WorkflowSerializationContext("namespace", "workflow");
    assertThrows(IllegalArgumentException.class, () -> HeaderUtils.withContext(codec, context));
    when(codec.withContext(any())).thenReturn(new ChainCodec(Collections.emptyList()));
    assertThrows(IllegalArgumentException.class, () -> HeaderUtils.withContext(codec, context));
  }

  @Test
  public void combinedConverterRejectsMissingContextualChainComponents() {
    for (boolean nested : new boolean[] {false, true}) {
      for (boolean sibling : new boolean[] {false, true}) {
        PayloadCodec component = mock(PayloadCodec.class);
        PayloadCodec source =
            nested ? new ChainCodec(Collections.singletonList(component)) : component;
        DataConverter valuesConverter = mock(DataConverter.class);
        CodecDataConverter converter =
            new CodecDataConverter(
                valuesConverter,
                sibling
                    ? Arrays.asList(source, new PrefixPayloadCodec())
                    : Collections.singletonList(source));
        WorkflowSerializationContext context =
            new WorkflowSerializationContext("namespace", "workflow");
        HeaderEncodingDataConverter enabled = new HeaderEncodingDataConverter(converter);
        PayloadCodec headers = HeaderUtils.resolveHeaderCodec(enabled);
        assertThrows(
            IllegalArgumentException.class, () -> HeaderUtils.withContext(headers, context));
        assertThrows(IllegalArgumentException.class, () -> enabled.withContext(context));
        when(component.withContext(any())).thenReturn(new ChainCodec(Collections.emptyList()));
        assertThrows(
            IllegalArgumentException.class, () -> HeaderUtils.withContext(headers, context));
        assertThrows(IllegalArgumentException.class, () -> enabled.withContext(context));
        verifyNoInteractions(valuesConverter);
      }
    }
  }

  @Test
  public void rejectsEmptyNestedComponentsAlongsideValidCodecs() {
    ChainCodec stage =
        new ChainCodec(
            Arrays.asList(new ChainCodec(Collections.emptyList()), new PrefixPayloadCodec()));
    DataConverterWithCodec direct = mock(DataConverterWithCodec.class);
    when(direct.getPayloadCodec()).thenReturn(stage);
    assertThrows(IllegalArgumentException.class, () -> new HeaderEncodingDataConverter(direct));

    PayloadCodec component = mock(PayloadCodec.class);
    WorkflowSerializationContext context =
        new WorkflowSerializationContext("namespace", "workflow");
    when(component.withContext(context)).thenReturn(stage);
    HeaderEncodingDataConverter converter =
        new HeaderEncodingDataConverter(
            new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(component)));
    assertThrows(IllegalArgumentException.class, () -> converter.withContext(context));
    verify(component).withContext(context);

    List<Payload> payloads =
        Collections.singletonList(DefaultDataConverter.STANDARD_INSTANCE.toPayload("value").get());
    assertEquals(payloads, stage.decode(stage.encode(payloads)));
  }

  @Test(expected = PayloadCodecException.class)
  public void rejectsCodecsThatChangeHeaderCardinality() {
    HeaderUtils.toHeaderGrpc(
        new Header(Collections.singletonMap("key", Payload.getDefaultInstance())),
        null,
        new CountingCodec() {
          @Override
          public List<Payload> encode(@Nonnull List<Payload> payloads) {
            return Collections.emptyList();
          }
        });
  }

  private static class CountingCodec implements PayloadCodec {
    private int encoded;
    private int decoded;

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      encoded++;
      return Collections.singletonList(
          Payload.newBuilder().setData(payloads.get(0).toByteString()).build());
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      decoded++;
      try {
        ByteString data = payloads.get(0).getData();
        return Collections.singletonList(Payload.parseFrom(data));
      } catch (Exception e) {
        throw new PayloadCodecException(e);
      }
    }
  }
}
