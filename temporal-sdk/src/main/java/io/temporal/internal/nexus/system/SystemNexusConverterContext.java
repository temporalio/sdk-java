package io.temporal.internal.nexus.system;

import io.temporal.common.converter.DataConverter;
import java.util.Objects;
import java.util.function.Supplier;

/** Scopes user-value conversion independently of generated bindings and system envelopes. */
public final class SystemNexusConverterContext {
  private static final ThreadLocal<DataConverter> USER_CONVERTER = new ThreadLocal<>();

  private SystemNexusConverterContext() {}

  /** Returns the user converter active in the current synchronous transfer conversion scope. */
  public static DataConverter currentDataConverter() {
    DataConverter converter = USER_CONVERTER.get();
    if (converter == null) {
      throw new IllegalStateException("System Nexus user converter context is not active");
    }
    return converter;
  }

  /** Runs a synchronous conversion with the user converter and restores any enclosing scope. */
  public static <T> T withDataConverter(DataConverter converter, Supplier<T> action) {
    Objects.requireNonNull(converter, "converter");
    DataConverter previous = USER_CONVERTER.get();
    USER_CONVERTER.set(converter);
    try {
      return action.get();
    } finally {
      if (previous == null) {
        USER_CONVERTER.remove();
      } else {
        USER_CONVERTER.set(previous);
      }
    }
  }
}
