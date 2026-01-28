package io.temporal.common;

import io.temporal.worker.WorkerDeploymentOptions;

/** Specifies when a workflow might move from a worker of one Build Id to another. */
@Experimental
public enum VersioningBehavior {
  /**
   * An unspecified versioning behavior. By default, workers opting into worker versioning will be
   * required to specify a behavior. See {@link
   * io.temporal.worker.WorkerOptions.Builder#setDeploymentOptions(WorkerDeploymentOptions)}.
   */
  UNSPECIFIED,
  /** The workflow will be pinned to the current Build ID unless manually moved. */
  PINNED,
  /**
   * The workflow will automatically move to the latest version (default Build ID of the task queue)
   * when the next task is dispatched.
   */
  AUTO_UPGRADE
}
