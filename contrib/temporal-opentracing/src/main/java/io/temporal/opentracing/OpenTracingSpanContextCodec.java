package io.temporal.opentracing;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.opentracing.codec.TextMapCodec;
import io.temporal.opentracing.codec.TextMapInjectExtractCodec;
import java.util.Map;

/**
 * Provides an interface for pluggable implementations that provide encoding/decoding of OpenTracing
 * {@link SpanContext}s back and forth to {@code Map<String, String>} that can be transported inside
 * Temporal Headers
 */
public interface OpenTracingSpanContextCodec {
  // default implementation
  OpenTracingSpanContextCodec TEXT_MAP_INJECT_EXTRACT_CODEC = TextMapInjectExtractCodec.INSTANCE;
  OpenTracingSpanContextCodec TEXT_MAP_CODEC = TextMapCodec.INSTANCE;

  Map<String, String> encode(SpanContext spanContext, Tracer tracer);

  SpanContext decode(Map<String, String> serializedSpanContext, Tracer tracer);
}
