package io.temporal.opentelemetry.codec;

import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A reusable implementation of {@link TextMapSetter} for {@code Map<String, String>} to avoid
 * creating lambda expressions or method references.
 */
public class MapTextMapSetter implements TextMapSetter<Map<String, String>> {
  /** Singleton instance for reuse. */
  public static final MapTextMapSetter INSTANCE = new MapTextMapSetter();

  private MapTextMapSetter() {}

  @Override
  public void set(@Nullable Map<String, String> carrier, String key, String value) {
    if (carrier != null) {
      carrier.put(key, value);
    }
  }
}
