package io.temporal.conf;

public final class EnvironmentVariableNames {
  /**
   * Specify this env variable to disable checks and enforcement for classes that are not intended
   * to be accessed from workflow code.
   *
   * <p>Not specifying it or setting it to "false" (case insensitive) leaves the checks enforced.
   *
   * <p>This option is exposed for backwards compatibility only and should never be enabled for any
   * new code or application.
   */
  public static final String DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS =
      "TEMPORAL_DISABLE_NON_WORKFLOW_CODE_ENFORCEMENTS";

  private EnvironmentVariableNames() {}
}
