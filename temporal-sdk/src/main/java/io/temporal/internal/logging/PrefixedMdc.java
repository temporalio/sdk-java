package io.temporal.internal.logging;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** Attaches prefix to MDC tags on put and remove. */
public class PrefixedMdc {
  private final String prefix;

  public PrefixedMdc(@Nullable String prefix) {
    this.prefix = prefix == null ? "" : prefix;
  }

  public PrefixedMdc put(String tag, String value) {
    MDC.put(prefix + tag, value);
    return this;
  }

  public PrefixedMdc remove(String tag) {
    MDC.remove(prefix + tag);
    return this;
  }
}
