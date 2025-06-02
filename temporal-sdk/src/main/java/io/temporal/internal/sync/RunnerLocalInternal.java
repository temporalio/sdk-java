package io.temporal.internal.sync;

import java.util.Optional;
import java.util.function.Supplier;

public final class RunnerLocalInternal<T> {

  private final boolean useCaching;

  public RunnerLocalInternal() {
    this.useCaching = false;
  }

  public RunnerLocalInternal(boolean useCaching) {
    this.useCaching = useCaching;
  }

  public T get(Supplier<? extends T> supplier) {
    Optional<Optional<T>> result;
    // Query handlers are special in that they are executing in a different context
    // than the main workflow execution threads. We need to fetch the runner local from the
    // correct context based on whether we are in a query handler or not.
    if (QueryDispatcher.isQueryHandler()) {
      result = QueryDispatcher.getWorkflowContext().getRunner().getRunnerLocal(this);
    } else {
      result = DeterministicRunnerImpl.currentThreadInternal().getRunner().getRunnerLocal(this);
    }
    T out = result.orElseGet(() -> Optional.ofNullable(supplier.get())).orElse(null);
    if (!result.isPresent() && useCaching && !QueryDispatcher.isQueryHandler()) {
      // This is the first time we've tried fetching this, and caching is enabled. Store it.
      set(out);
    }
    return out;
  }

  public void set(T value) {
    DeterministicRunnerImpl.currentThreadInternal().getRunner().setRunnerLocal(this, value);
  }
}
