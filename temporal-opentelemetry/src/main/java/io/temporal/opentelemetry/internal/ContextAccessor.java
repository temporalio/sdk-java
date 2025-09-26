package io.temporal.opentelemetry.internal;

import com.google.common.reflect.TypeToken;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.StdConverterBackwardsCompatAdapter;
import io.temporal.common.interceptors.Header;
import io.temporal.opentelemetry.OpenTelemetryOptions;
import io.temporal.opentelemetry.OpenTelemetrySpanContextCodec;
import io.temporal.opentelemetry.codec.MapTextMapGetter;
import io.temporal.opentelemetry.codec.MapTextMapSetter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class ContextAccessor {
  private static final String TRACER_HEADER_KEY = "_tracer-data";
  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  private final OpenTelemetrySpanContextCodec codec;
  private final TextMapPropagator propagator;

  public ContextAccessor(OpenTelemetryOptions options) {
    this.codec = options.getSpanContextCodec();
    this.propagator = options.getPropagator();
  }

  public Span writeSpanContextToHeader(
      Supplier<Span> spanSupplier, Header toHeader, Tracer tracer) {
    Span span = spanSupplier.get();
    writeSpanContextToHeader(Context.current().with(span), toHeader, tracer);
    return span;
  }

  public void writeSpanContextToHeader(Context context, Header header, Tracer tracer) {
    // Create a new map and use the propagator directly to ensure both trace context AND baggage are
    // injected
    Map<String, String> serializedContext = new HashMap<>();
    propagator.inject(context, serializedContext, MapTextMapSetter.INSTANCE);

    Optional<Payload> payload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(serializedContext);
    header.getValues().put(TRACER_HEADER_KEY, payload.get());
  }

  public Context readSpanContextFromHeader(Header header, Tracer tracer) {
    Payload payload = header.getValues().get(TRACER_HEADER_KEY);
    if (payload == null) {
      return Context.current();
    }
    @SuppressWarnings("unchecked")
    Map<String, String> serializedContext =
        StdConverterBackwardsCompatAdapter.fromPayload(
            payload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
    // Use the propagator directly to ensure both trace context AND baggage are extracted
    return propagator.extract(Context.current(), serializedContext, MapTextMapGetter.INSTANCE);
  }

  public Span writeSpanContextToHeader(
      Supplier<Span> spanSupplier, Map<String, String> header, Tracer tracer) {
    Span span = spanSupplier.get();
    writeSpanContextToHeader(Context.current().with(span), header, tracer);
    return span;
  }

  public void writeSpanContextToHeader(Context context, Map<String, String> header, Tracer tracer) {
    propagator.inject(context, header, MapTextMapSetter.INSTANCE);
  }

  public Context readSpanContextFromHeader(Map<String, String> header, Tracer tracer) {
    return propagator.extract(Context.current(), header, MapTextMapGetter.INSTANCE);
  }
}
