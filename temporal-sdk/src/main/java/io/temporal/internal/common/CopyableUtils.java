package io.temporal.internal.common;

import io.temporal.common.Copyable;
import java.util.Objects;

/** Validates copies before they cross the local activity boundary. */
public final class CopyableUtils {
  private CopyableUtils() {}

  public static Object copy(Object value) {
    Copyable<?> copy =
        Objects.requireNonNull(
            ((Copyable<?>) value).copy(), "Copyable.copy() must not return null");
    if (!value.getClass().isInstance(copy)) {
      throw new IllegalArgumentException(
          "Copyable.copy() must return a Copyable compatible with " + value.getClass().getName());
    }
    return copy;
  }
}
