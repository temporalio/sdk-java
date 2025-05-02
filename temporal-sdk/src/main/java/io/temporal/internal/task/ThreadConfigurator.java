package io.temporal.internal.task;

/**
 * Function interface for {@link VirtualThreadDelegate#newVirtualThreadExecutor(ThreadConfigurator)}
 * called for every thread created.
 */
@FunctionalInterface
public interface ThreadConfigurator {
  /** Invoked for every thread created by {@link VirtualThreadDelegate#newVirtualThreadExecutor}. */
  void configure(Thread t);
}
