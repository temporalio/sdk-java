package io.temporal.internal.task;

import java.util.concurrent.ExecutorService;

/**
 * Internal delegate for virtual thread handling on JDK 21. This is a dummy version for reachability
 * on JDK <21.
 */
public final class VirtualThreadDelegate {
  public static ExecutorService newVirtualThreadExecutor(ThreadConfigurator configurator) {
    throw new UnsupportedOperationException("Virtual threads not supported on JDK <21");
  }

  private VirtualThreadDelegate() {}
}
