package io.temporal.internal.util;

import javax.annotation.Nullable;

/** A mutable single-value container, usable where a effectively-final local variable is needed. */
public final class Box<T> {
  private @Nullable T value;

  public Box() {}

  public Box(@Nullable T value) {
    this.value = value;
  }

  public @Nullable T get() {
    return value;
  }

  public void set(@Nullable T value) {
    this.value = value;
  }
}
