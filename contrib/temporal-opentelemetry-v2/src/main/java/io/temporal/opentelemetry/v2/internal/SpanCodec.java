package io.temporal.opentelemetry.v2.internal;

import com.google.common.reflect.TypeToken;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.StdConverterBackwardsCompatAdapter;
import io.temporal.common.interceptors.Header;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Serializes the current span and baggage into Temporal and Nexus headers and reads them back.
 *
 * <p>Temporal headers carry one {@link Properties} payload under the configured key. Nexus headers
 * are flat and use HTTP header semantics, so they are read case-insensitively.
 */
final class SpanCodec {
  private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        @Nullable
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };
  private static final TextMapSetter<Properties> PROPERTIES_SETTER = Properties::setProperty;
  private static final TextMapGetter<Properties> PROPERTIES_GETTER =
      new TextMapGetter<Properties>() {
        @Override
        public Iterable<String> keys(Properties carrier) {
          return carrier.stringPropertyNames();
        }

        @Override
        @Nullable
        public String get(Properties carrier, String key) {
          return carrier.getProperty(key);
        }
      };
  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  private final TextMapPropagator propagator;
  private final String headerKey;

  SpanCodec(String headerKey) {
    this.propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
    this.headerKey = headerKey;
  }

  /** The current context extended with the span and baggage carried by {@code header}. */
  Context read(Header header) {
    Payload payload = header.getValues().get(headerKey);
    if (payload == null) {
      return Context.current();
    }
    return extract(decode(payload), PROPERTIES_GETTER);
  }

  /** The current context extended with the span and baggage carried by {@code nexusHeaders}. */
  Context read(Map<String, String> nexusHeaders) {
    // Nexus headers use HTTP header semantics, so the propagator must see them case-insensitively.
    // See https://opentelemetry.io/docs/specs/otel/context/api-propagators/#get.
    Map<String, String> carrier = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    carrier.putAll(nexusHeaders);
    return extract(carrier, MAP_GETTER);
  }

  /** Writes the current span and baggage into {@code header}; leaves it untouched if empty. */
  void write(Header header) {
    Properties carrier = new Properties();
    propagator.inject(Context.current(), carrier, PROPERTIES_SETTER);
    if (!carrier.isEmpty()) {
      header.getValues().put(headerKey, encode(carrier));
    }
  }

  /** Removes the tracing header from {@code header}. */
  void clear(Header header) {
    header.getValues().remove(headerKey);
  }

  /** Writes the current span and baggage into {@code nexusHeaders}. */
  void write(Map<String, String> nexusHeaders) {
    propagator.inject(Context.current(), nexusHeaders, MAP_SETTER);
  }

  private <C> Context extract(C carrier, TextMapGetter<C> getter) {
    Context current = Context.current();
    return propagator.extract(current, carrier, getter);
  }

  private static Payload encode(Properties carrier) {
    return DefaultDataConverter.STANDARD_INSTANCE.toPayload(carrier).get();
  }

  static Properties decode(Payload payload) {
    Map<?, ?> decoded =
        StdConverterBackwardsCompatAdapter.fromPayload(
            payload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
    Properties properties = new Properties();
    properties.putAll(decoded);
    return properties;
  }
}
