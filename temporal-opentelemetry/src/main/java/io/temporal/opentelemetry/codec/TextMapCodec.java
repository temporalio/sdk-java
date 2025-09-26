package io.temporal.opentelemetry.codec;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.opentelemetry.OpenTelemetrySpanContextCodec;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Implementation of {@link OpenTelemetrySpanContextCodec} that uses the specified TextMapPropagator
 * for transporting spans between services.
 *
 * <p>NOTE: This implementation is identical to {@link TextMapInjectExtractCodec}. Unlike in the
 * OpenTracing implementation where TextMapCodec was a fallback using a different propagation
 * format, in OpenTelemetry there's no distinction between the two codecs as OpenTelemetry has a
 * unified API for context propagation.
 *
 * <p>Prefer using {@link TextMapInjectExtractCodec} directly as it is the default implementation.
 */
public class TextMapCodec implements OpenTelemetrySpanContextCodec {
  public static final TextMapCodec INSTANCE = new TextMapCodec();

  @Override
  public Map<String, String> encode(@Nullable Context context, TextMapPropagator propagator) {
    if (context == null) {
      return new HashMap<>();
    }

    Map<String, String> result = new HashMap<>();
    propagator.inject(context, result, MapTextMapSetter.INSTANCE);
    return result;
  }

  @Override
  public Context decode(Map<String, String> serializedContext, TextMapPropagator propagator) {
    return propagator.extract(Context.current(), serializedContext, MapTextMapGetter.INSTANCE);
  }
}
