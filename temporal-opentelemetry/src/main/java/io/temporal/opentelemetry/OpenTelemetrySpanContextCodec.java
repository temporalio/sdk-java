package io.temporal.opentelemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.opentelemetry.codec.TextMapCodec;
import io.temporal.opentelemetry.codec.TextMapInjectExtractCodec;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provides an interface for pluggable implementations that provide encoding/decoding of
 * OpenTelemetry {@link Context}s back and forth to {@code Map<String, String>} that can be
 * transported inside Temporal Headers
 */
public interface OpenTelemetrySpanContextCodec {
  // default implementation
  OpenTelemetrySpanContextCodec TEXT_MAP_INJECT_EXTRACT_CODEC = TextMapInjectExtractCodec.INSTANCE;
  OpenTelemetrySpanContextCodec TEXT_MAP_CODEC = TextMapCodec.INSTANCE;

  Map<String, String> encode(@Nullable Context context, TextMapPropagator propagator);

  Context decode(Map<String, String> serializedContext, TextMapPropagator propagator);
}
