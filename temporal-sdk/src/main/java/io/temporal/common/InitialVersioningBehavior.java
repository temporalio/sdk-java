package io.temporal.common;

/**
 * Specifies the versioning behavior for the first task of a new workflow run started via
 * continue-as-new.
 */
@Experimental
public enum InitialVersioningBehavior {
  /**
   * Start the new run with {@link VersioningBehavior#AUTO_UPGRADE} behavior for the first task,
   * upgrading to the latest version. After the first workflow task completes, the workflow uses
   * whatever versioning behavior is specified in the workflow code.
   */
  AUTO_UPGRADE,

  /**
   * Use the Ramping Version of the workflow's task queue at start time, regardless of the
   * workflow's Target Version.
   *
   * <p>After the first workflow task completes, the workflow uses whatever {@link
   * VersioningBehavior} is specified in the workflow code. If there is no Ramping Version by the
   * time that the first workflow task is dispatched, it is sent to the Current Version.
   *
   * <p>It is highly discouraged to use this if the workflow is annotated with {@link
   * VersioningBehavior#AUTO_UPGRADE} behavior, because this setting only applies to the first task
   * of the workflow. If, after the first task, the workflow is AutoUpgrade, it behaves like a
   * normal AutoUpgrade workflow and goes to the Target Version, which may be the Current Version
   * instead of the Ramping Version.
   *
   * <p>Note that if the workflow being continued has a Pinned override, that override is inherited
   * by the new workflow run regardless of the {@link InitialVersioningBehavior} specified in the
   * continue-as-new command. Versioning Override always takes precedence until it is removed
   * manually via UpdateWorkflowExecutionOptions.
   */
  USE_RAMPING_VERSION
}
