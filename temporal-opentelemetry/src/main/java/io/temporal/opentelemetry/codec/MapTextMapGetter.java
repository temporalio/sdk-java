package io.temporal.opentelemetry.codec;

import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A reusable implementation of {@link TextMapGetter} for {@code Map<String, String>} to avoid
 * creating anonymous inner classes.
 */
public class MapTextMapGetter implements TextMapGetter<Map<String, String>> {
  /** Singleton instance for reuse. */
  public static final MapTextMapGetter INSTANCE = new MapTextMapGetter();

  private MapTextMapGetter() {}

  @Override
  public Iterable<String> keys(Map<String, String> carrier) {
    return carrier.keySet();
  }

  @Nullable
  @Override
  public String get(Map<String, String> carrier, String key) {
    return carrier.get(key);
  }
}
