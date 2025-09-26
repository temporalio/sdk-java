package io.temporal.opentelemetry.codec;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.opentelemetry.OpenTelemetrySpanContextCodec;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Default implementation of {@link OpenTelemetrySpanContextCodec} that uses the specified
 * TextMapPropagator for transporting spans between services.
 *
 * <p>IMPORTANT: This implementation ensures that both trace context AND baggage information is
 * properly injected and extracted when using the OpenTelemetry propagator.
 */
public class TextMapInjectExtractCodec implements OpenTelemetrySpanContextCodec {
  public static final TextMapInjectExtractCodec INSTANCE = new TextMapInjectExtractCodec();

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
