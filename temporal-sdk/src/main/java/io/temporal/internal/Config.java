package io.temporal.internal;

public final class Config {
  private Config() {}

  /** Force new workflow task after workflow task timeout multiplied by this coefficient. */
  public static final double WORKFLOW_TASK_HEARTBEAT_COEFFICIENT = 4d / 5d;

  /**
   * Limit how many eager activities can be requested by the SDK in one workflow task completion
   * response.
   */
  public static int EAGER_ACTIVITIES_LIMIT = 3;
}
