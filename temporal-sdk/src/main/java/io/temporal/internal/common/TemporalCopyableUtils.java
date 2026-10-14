package io.temporal.internal.common;

import io.temporal.common.TemporalCopyable;
import java.util.Objects;

/** Validates copies before they cross the local activity boundary. */
public final class TemporalCopyableUtils {
  private TemporalCopyableUtils() {}

  public static Object copy(Object value) {
    TemporalCopyable<?> copy =
        Objects.requireNonNull(
            ((TemporalCopyable<?>) value).copy(), "TemporalCopyable.copy() must not return null");
    if (!value.getClass().isInstance(copy)) {
      throw new IllegalArgumentException(
          "TemporalCopyable.copy() must return a TemporalCopyable compatible with "
              + value.getClass().getName());
    }
    return copy;
  }
}
