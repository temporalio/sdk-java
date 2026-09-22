package io.temporal.opentracing.codec;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import java.util.HashMap;
import java.util.Map;

/**
 * This Encoder uses {@link io.opentracing.propagation.Format.Builtin#TEXT_MAP} for both
 * serialization and deserialization. This is not a default strategy, for default strategy see
 * {@link TextMapInjectExtractCodec}. This strategy was added as a workaround if the specific
 * opentracing client implementation doesn't support {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_INJECT} and {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_EXTRACT}
 */
public class TextMapCodec implements OpenTracingSpanContextCodec {
  public static final TextMapCodec INSTANCE = new TextMapCodec();

  @Override
  public Map<String, String> encode(SpanContext spanContext, Tracer tracer) {
    Map<String, String> serialized = new HashMap<>();
    tracer.inject(spanContext, Format.Builtin.TEXT_MAP, new TextMapAdapter(serialized));
    return serialized;
  }

  @Override
  public SpanContext decode(Map<String, String> serializedSpanContext, Tracer tracer) {
    return tracer.extract(Format.Builtin.TEXT_MAP, new TextMapAdapter(serializedSpanContext));
  }
}
