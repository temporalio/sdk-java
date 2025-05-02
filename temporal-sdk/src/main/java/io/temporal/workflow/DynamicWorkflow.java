package io.temporal.workflow;

import io.temporal.common.VersioningBehavior;
import io.temporal.common.converter.EncodedValues;
import io.temporal.worker.WorkerDeploymentOptions;

/**
 * Use DynamicWorkflow to implement any number of workflow types dynamically. When a workflow
 * implementation type that extends DynamicWorkflow is registered, it is used to implement any
 * workflow type that is not implicitly registered with the {@link io.temporal.worker.Worker}. Only
 * one type that implements DynamicWorkflow per worker is allowed.
 *
 * <p>The main use case for DynamicWorkflow is an implementation of custom Domain Specific Languages
 * (DSLs). A single implementation can implement a workflow type which definition is dynamically
 * loaded from some external source.
 *
 * <p>Use {@link Workflow#getInfo()} to query information about the workflow type that should be
 * implemented dynamically.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register signal and query listeners. Consider
 * using {@link DynamicSignalHandler} and {@link DynamicQueryHandler} to implement handlers that can
 * support any signal or query type dynamically.
 *
 * <p>All the determinism rules still apply to workflows that implement this interface.
 *
 * @see io.temporal.activity.DynamicActivity
 */
public interface DynamicWorkflow {
  Object execute(EncodedValues args);

  /**
   * @return The versioning behavior of this dynamic workflow. See {@link
   *     io.temporal.worker.WorkerOptions.Builder#setDeploymentOptions(WorkerDeploymentOptions)} for
   *     more on worker versioning. This method must be overridden if {@link
   *     WorkerDeploymentOptions.Builder#setUseVersioning(boolean)} is true. and no default behavior
   *     is specified.
   */
  default VersioningBehavior getVersioningBehavior() {
    return VersioningBehavior.UNSPECIFIED;
  }
}
