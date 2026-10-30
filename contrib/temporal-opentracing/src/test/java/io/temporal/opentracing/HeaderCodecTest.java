package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import io.opentracing.Span;
import io.opentracing.mock.MockTracer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.Test;

public class HeaderCodecTest {
  @Test
  public void tracingKeepsStandardSerializationAndUsesHeaderCodecExactlyOnce() {
    for (boolean enabled : new boolean[] {false, true}) {
      MockTracer tracer = new MockTracer();
      ContextAccessor accessor =
          new ContextAccessor(
              OpenTracingOptions.newBuilder().setTracer(tracer).setEncodeHeaders(enabled).build());
      Span span = tracer.buildSpan("parent").start();
      CountingCodec codec = new CountingCodec();
      DataConverter valuesConverter = mock(DataConverter.class);
      CodecDataConverter converter =
          new CodecDataConverter(valuesConverter, Collections.singletonList(codec));
      PayloadCodec headerCodec = HeaderUtils.resolveHeaderCodec(converter);
      Header header = Header.empty();
      accessor.writeSpanContextToHeader(span.context(), header, tracer);
      Payload plaintext = DefaultDataConverter.STANDARD_INSTANCE.toPayload("other producer").get();
      header.getValues().put("other", plaintext);
      io.temporal.api.common.v1.Header wire = HeaderUtils.toHeaderGrpc(header, null, headerCodec);
      Header received = new Header(wire, headerCodec);
      assertEquals(plaintext, received.getValues().get("other"));

      assertEquals(
          span.context().toTraceId(),
          accessor.readSpanContextFromHeader(received, tracer).toTraceId());
      assertEquals(enabled ? 1 : 0, codec.encoded);
      assertEquals(enabled ? 1 : 0, codec.decoded);
      assertEquals(wire.getFieldsMap(), received.getValues());
      verifyNoInteractions(valuesConverter);
      span.finish();
      tracer.close();
    }
  }

  static class CountingCodec implements PayloadCodec {
    private int encoded;
    private int decoded;

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      encoded++;
      return Collections.singletonList(
          Payload.newBuilder()
              .putMetadata("encoding", ByteString.copyFromUtf8("binary/test-tracing"))
              .setData(payloads.get(0).toByteString())
              .build());
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      if (!ByteString.copyFromUtf8("binary/test-tracing")
          .equals(payloads.get(0).getMetadataOrDefault("encoding", ByteString.EMPTY))) {
        return payloads;
      }
      decoded++;
      try {
        return Collections.singletonList(Payload.parseFrom(payloads.get(0).getData()));
      } catch (Exception e) {
        throw new PayloadCodecException(e);
      }
    }
  }
}
