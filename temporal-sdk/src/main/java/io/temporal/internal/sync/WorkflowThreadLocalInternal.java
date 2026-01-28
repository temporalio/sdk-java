package io.temporal.internal.sync;

import java.util.Optional;
import java.util.function.Supplier;

public final class WorkflowThreadLocalInternal<T> {

  private final boolean useCaching;

  public WorkflowThreadLocalInternal() {
    this(false);
  }

  public WorkflowThreadLocalInternal(boolean useCaching) {
    this.useCaching = useCaching;
  }

  public T get(Supplier<? extends T> supplier) {
    Optional<Optional<T>> result =
        DeterministicRunnerImpl.currentThreadInternal().getThreadLocal(this);
    T out = result.orElseGet(() -> Optional.ofNullable(supplier.get())).orElse(null);
    if (!result.isPresent() && useCaching) {
      // This is the first time we've tried fetching this, and caching is enabled. Store it.
      set(out);
    }
    return out;
  }

  public void set(T value) {
    DeterministicRunnerImpl.currentThreadInternal().setThreadLocal(this, value);
  }
}
