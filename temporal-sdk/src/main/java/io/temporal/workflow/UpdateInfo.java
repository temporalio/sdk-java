package io.temporal.workflow;

/** Provides information about the current workflow Update. */
public interface UpdateInfo {
  /**
   * @return Update name
   */
  String getUpdateName();

  /**
   * @return Update ID
   */
  String getUpdateId();
}
