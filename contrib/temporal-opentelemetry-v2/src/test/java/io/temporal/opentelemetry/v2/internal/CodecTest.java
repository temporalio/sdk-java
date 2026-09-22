package io.temporal.opentelemetry.v2.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.common.interceptors.Header;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link SpanCodec} write/read on Temporal headers and Nexus maps. */
public class CodecTest {
  private static final String HEADER_KEY = "_tracer-data";
  private static final SpanContext SPAN =
      SpanContext.create(
          TraceId.fromLongs(0, 1),
          SpanId.fromLong(2),
          TraceFlags.getSampled(),
          TraceState.getDefault());
  private static final Baggage BAGGAGE = Baggage.builder().put("key", "value").build();

  private SpanCodec codec;

  @Before
  public void installPropagators() {
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(
        OpenTelemetry.propagating(
            ContextPropagators.create(
                TextMapPropagator.composite(
                    W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()))));
    codec = new SpanCodec(HEADER_KEY);
  }

  @After
  public void resetGlobalOpenTelemetry() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  public void writesAndReadsTemporalHeaders() {
    Header header = new Header(new HashMap<>());
    try (Scope ignored = current().makeCurrent()) {
      codec.write(header);
    }
    assertPropagated(codec.read(header));
  }

  @Test
  public void writesAndReadsNexusHeaders() {
    Map<String, String> headers = new HashMap<>();
    try (Scope ignored = current().makeCurrent()) {
      codec.write(headers);
    }
    assertPropagated(codec.read(headers));
  }

  @Test
  public void leavesTemporalHeaderAloneWhenNothingToInject() {
    Header header = new Header(new HashMap<>());
    try (Scope ignored = Context.root().makeCurrent()) {
      codec.write(header);
    }
    assertNull(header.getValues().get(HEADER_KEY));
  }

  @Test
  public void missingTemporalHeaderKeepsCurrentContext() {
    try (Scope ignored = current().makeCurrent()) {
      assertPropagated(codec.read(new Header(new HashMap<>())));
    }
  }

  @Test
  public void readsNexusHeadersCaseInsensitively() {
    Map<String, String> written = new HashMap<>();
    try (Scope ignored = current().makeCurrent()) {
      codec.write(written);
    }
    Map<String, String> upper = new HashMap<>();
    written.forEach((key, value) -> upper.put(key.toUpperCase(), value));
    assertPropagated(codec.read(upper));
  }

  @Test
  public void preservesNexusHeaderKeyCase() {
    Map<String, String> headers = new HashMap<>();
    try (Scope ignored = current().makeCurrent()) {
      codec.write(headers);
    }
    assertTrue(headers.containsKey("traceparent"));
    assertTrue(headers.containsKey("baggage"));
  }

  @Test
  public void decodesPropertiesPayload() {
    Properties carrier = new Properties();
    carrier.setProperty("traceparent", "00-trace-id-span-id-01");
    Payload payload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(carrier).get();
    assertEquals(carrier, SpanCodec.decode(payload));
  }

  @Test
  public void decodesLegacyMapPayloadViaGlobalConverter() {
    Map<String, String> carrier = new HashMap<>();
    carrier.put("traceparent", "00-trace-id-span-id-01");
    Payload payload =
        Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("legacy/json"))
            .build();

    DataConverter original = GlobalDataConverter.get();
    GlobalDataConverter.register(
        new DefaultDataConverter(new JacksonJsonPayloadConverter()) {
          @Override
          public <T> T fromPayload(Payload ignored, Class<T> valueClass, Type type) {
            return valueClass.cast(carrier);
          }
        });
    try {
      assertEquals(carrier, SpanCodec.decode(payload));
    } finally {
      GlobalDataConverter.register(original);
    }
  }

  private static Context current() {
    return Context.root().with(Span.wrap(SPAN)).with(BAGGAGE);
  }

  private static void assertPropagated(Context context) {
    SpanContext span = Span.fromContext(context).getSpanContext();
    assertEquals(SPAN.getTraceId(), span.getTraceId());
    assertEquals(SPAN.getSpanId(), span.getSpanId());
    assertEquals("value", Baggage.fromContext(context).getEntryValue("key"));
  }
}
