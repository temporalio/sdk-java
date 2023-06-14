package io.temporal.common;

/**
 * Indicates whether the user intends certain commands to be run on a compatible worker build ID
 * version or not.
 */
public enum VersioningIntent {
  /**
   * Indicates that the SDK should choose the most sensible default behavior for the type of
   * command, accounting for whether the command will be run on the same task queue as the current
   * worker.
   */
  VERSIONING_INTENT_UNSPECIFIED,
  /**
   * Indicates that the command should run on a worker with compatible version if possible. It may
   * not be possible if the target task queue does not also have knowledge of the current worker's
   * build ID.
   */
  VERSIONING_INTENT_COMPATIBLE,
  /**
   * Indicates that the command should run on the target task queue's current overall-default build
   * ID.
   */
  VERSIONING_INTENT_DEFAULT;

  public boolean determineUseCompatibleFlag(boolean destinationTaskQueueIsSame) {
    switch (this) {
      case VERSIONING_INTENT_COMPATIBLE:
        return true;
      case VERSIONING_INTENT_DEFAULT:
        return false;
      default:
        // In the unspecified case, we want to stick to the compatible version as long as the
        // destination queue is the same.
        return destinationTaskQueueIsSame;
    }
  }
}
