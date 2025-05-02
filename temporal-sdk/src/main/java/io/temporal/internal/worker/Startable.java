package io.temporal.internal.worker;

public interface Startable extends WorkerWithLifecycle {
  /**
   * This method is not required to be idempotent. It is expected to be called only once.
   *
   * @return true if the start was successful, false if the worker configuration renders the start
   *     obsolete (like no types are registered)
   */
  boolean start();
}
