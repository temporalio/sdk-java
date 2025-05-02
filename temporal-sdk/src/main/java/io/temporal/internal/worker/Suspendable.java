package io.temporal.internal.worker;

public interface Suspendable extends WorkerWithLifecycle {

  /**
   * Do not make new poll requests. Outstanding long polls still can return tasks after this method
   * was called.
   */
  void suspendPolling();

  /** Allow new poll requests. */
  void resumePolling();

  boolean isSuspended();
}
