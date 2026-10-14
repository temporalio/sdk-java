package io.temporal.internal.activity;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.Copyable;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.CopyableUtils;
import java.util.Optional;
import javax.annotation.Nullable;

/** A scheduling-time snapshot that is never exposed to an activity attempt. */
public final class LocalActivityArguments {
  private final Object[] snapshot;

  private LocalActivityArguments(Object[] snapshot) {
    this.snapshot = snapshot;
  }

  /**
   * Returns a snapshot only when the entire argument list supports copying. Otherwise, the caller
   * must use the data converter for the complete list to preserve its batch conversion semantics.
   */
  @Nullable
  public static LocalActivityArguments capture(@Nullable Object[] arguments) {
    if (arguments == null) {
      arguments = new Object[0];
    }
    for (Object argument : arguments) {
      if (argument != null && !(argument instanceof Copyable)) {
        return null;
      }
    }
    return new LocalActivityArguments(copy(arguments));
  }

  /** Returns independent arguments for each attempt, including retries after a workflow timer. */
  public Object[] copy() {
    return copy(snapshot);
  }

  public boolean canCopyTo(Class<?>[] parameterTypes) {
    if (parameterTypes.length != snapshot.length) {
      return false;
    }
    for (int i = 0; i < snapshot.length; i++) {
      if (snapshot[i] == null
          ? parameterTypes[i].isPrimitive()
          : !parameterTypes[i].isInstance(snapshot[i])) {
        return false;
      }
    }
    return true;
  }

  /** Preserves conversion behavior for untyped calls with different argument types or counts. */
  public Optional<Payloads> toPayloads(DataConverter converter) {
    return converter.toPayloads(snapshot);
  }

  private static Object[] copy(Object[] arguments) {
    Object[] copy = new Object[arguments.length];
    for (int i = 0; i < arguments.length; i++) {
      copy[i] = arguments[i] == null ? null : CopyableUtils.copy(arguments[i]);
    }
    return copy;
  }
}
