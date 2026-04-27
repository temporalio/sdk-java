package io.temporal.opentracing.codec;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtractAdapter;
import io.opentracing.propagation.TextMapInjectAdapter;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import java.util.HashMap;
import java.util.Map;

/**
 * This is the default implementation that uses {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_INJECT} and {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_EXTRACT} specific formats for serialization
 * and deserialization respectively
 */
public class TextMapInjectExtractCodec implements OpenTracingSpanContextCodec {
  public static final TextMapInjectExtractCodec INSTANCE = new TextMapInjectExtractCodec();

  @Override
  public Map<String, String> encode(SpanContext spanContext, Tracer tracer) {
    Map<String, String> serialized = new HashMap<>();
    tracer.inject(
        spanContext, Format.Builtin.TEXT_MAP_INJECT, new TextMapInjectAdapter(serialized));
    return serialized;
  }

  @Override
  public SpanContext decode(Map<String, String> serializedSpanContext, Tracer tracer) {
    return tracer.extract(
        Format.Builtin.TEXT_MAP_EXTRACT, new TextMapExtractAdapter(serializedSpanContext));
  }
}
