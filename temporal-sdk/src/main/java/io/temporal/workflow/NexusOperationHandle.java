package io.temporal.workflow;

/**
 * OperationHandle is used to interact with a scheduled nexus operation. Created through {@link
 * Workflow#startNexusOperation}.
 */
public interface NexusOperationHandle<R> {
  /**
   * Returns a promise that is resolved when the operation reaches the STARTED state. For
   * synchronous operations, this will be resolved at the same time as the promise from
   * executeAsync. For asynchronous operations, this promises is resolved independently. If the
   * operation is unsuccessful, this promise will throw the same exception as executeAsync. Use this
   * method to extract the Operation ID of an asynchronous operation. OperationID will be empty for
   * synchronous operations. If the workflow completes before this promise is ready then the
   * operation might not start at all.
   *
   * @return promise that becomes ready once the operation has started.
   */
  Promise<NexusOperationExecution> getExecution();

  /** Returns a promise that will be resolved when the operation completes. */
  Promise<R> getResult();
}
