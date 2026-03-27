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
  AUTO_UPGRADE
}
