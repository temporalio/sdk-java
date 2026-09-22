package io.temporal.opentracing.internal;

import com.google.common.reflect.TypeToken;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.StdConverterBackwardsCompatAdapter;
import io.temporal.common.interceptors.Header;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class ContextAccessor {
  private static final String TRACER_HEADER_KEY = "_tracer-data";
  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  private final OpenTracingSpanContextCodec codec;

  public ContextAccessor(OpenTracingOptions options) {
    this.codec = options.getSpanContextCodec();
  }

  public Span writeSpanContextToHeader(
      Supplier<Span> spanSupplier, Header toHeader, Tracer tracer) {
    Span span = spanSupplier.get();
    writeSpanContextToHeader(span.context(), toHeader, tracer);
    return span;
  }

  public void writeSpanContextToHeader(SpanContext spanContext, Header header, Tracer tracer) {
    Map<String, String> serializedSpanContext = codec.encode(spanContext, tracer);
    Optional<Payload> payload =
        DefaultDataConverter.STANDARD_INSTANCE.toPayload(serializedSpanContext);
    header.getValues().put(TRACER_HEADER_KEY, payload.get());
  }

  public SpanContext readSpanContextFromHeader(Header header, Tracer tracer) {
    Payload payload = header.getValues().get(TRACER_HEADER_KEY);
    if (payload == null) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, String> serializedSpanContext =
        StdConverterBackwardsCompatAdapter.fromPayload(
            payload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
    return codec.decode(serializedSpanContext, tracer);
  }

  public Span writeSpanContextToHeader(
      Supplier<Span> spanSupplier, Map<String, String> header, Tracer tracer) {
    Span span = spanSupplier.get();
    writeSpanContextToHeader(span.context(), header, tracer);
    return span;
  }

  public void writeSpanContextToHeader(
      SpanContext spanContext, Map<String, String> header, Tracer tracer) {
    tracer.inject(spanContext, Format.Builtin.HTTP_HEADERS, new TextMapAdapter(header));
  }

  public SpanContext readSpanContextFromHeader(Map<String, String> header, Tracer tracer) {
    return tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapAdapter(header));
  }
}
