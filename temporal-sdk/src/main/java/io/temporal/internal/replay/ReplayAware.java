package io.temporal.internal.replay;

public interface ReplayAware {
  /**
   * <code>true</code> indicates if workflow is replaying already processed events to reconstruct it
   * state. <code>false</code> indicates that code is making forward process for the first time. For
   * example can be used to avoid duplicating log records due to replay.
   */
  boolean isReplaying();
}
