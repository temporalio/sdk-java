package io.temporal.common;

/** Specifies when a workflow might move from a worker of one Build Id to another. */
@Experimental
public enum VersioningBehavior {
  /**
   * An unspecified versioning behavior. By default, workers opting into worker versioning will be
   * required to specify a behavior. TODO: Link to documentation.
   */
  VERSIONING_BEHAVIOR_UNSPECIFIED,
  /** The workflow will be pinned to the current Build ID unless manually moved. */
  VERSIONING_BEHAVIOR_PINNED,
  /**
   * The workflow will automatically move to the latest version (default Build ID of the task queue)
   * when the next task is dispatched.
   */
  VERSIONING_BEHAVIOR_AUTO_UPGRADE
}
