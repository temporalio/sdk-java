package io.temporal.internal.worker;

public enum WorkerLifecycleState {
  /** The worker was created but never started */
  NOT_STARTED,
  /** Ready to accept and process tasks */
  ACTIVE,
  /** May be absent from a worker state machine is the worker is not {@link Suspendable} */
  SUSPENDED,
  /**
   * Shutdown is requested on the worker, and it is completing with the outstanding tasks before
   * being {@link #TERMINATED}. The worker MAY reject new tasks to be processed if any internals are
   * already being released.
   */
  SHUTDOWN,
  /**
   * The final state of the worker, all internal resources are released, the worker SHOULD reject
   * any new tasks to be processed
   */
  TERMINATED
}
