package io.temporal.internal.common.env;

import javax.annotation.Nullable;

public final class EnvironmentVariableUtils {
  private EnvironmentVariableUtils() {}

  /**
   * @return false if the environment variable is not set, "false" or "0". Otherwise, returns true.
   */
  public static boolean readBooleanFlag(String variableName) {
    String variableValue = SystemEnvironmentVariablesProvider.INSTANCE.getenv(variableName);
    if (variableValue == null) {
      return false;
    }
    variableValue = variableValue.trim();
    return (!Boolean.FALSE.toString().equalsIgnoreCase(variableValue)
        && !"0".equals(variableValue));
  }

  @Nullable
  public static String readString(String variableName) {
    return SystemEnvironmentVariablesProvider.INSTANCE.getenv(variableName);
  }
}
