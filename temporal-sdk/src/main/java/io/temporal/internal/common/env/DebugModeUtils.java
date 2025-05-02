package io.temporal.internal.common.env;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.conf.EnvironmentVariableNames;

public final class DebugModeUtils {
  private static boolean TEMPORAL_DEBUG_MODE =
      EnvironmentVariableUtils.readBooleanFlag(EnvironmentVariableNames.TEMPORAL_DEBUG);

  private DebugModeUtils() {}

  public static boolean isTemporalDebugModeOn() {
    return TEMPORAL_DEBUG_MODE;
  }

  @VisibleForTesting
  public static void override(boolean debugMode) {
    TEMPORAL_DEBUG_MODE = debugMode;
  }

  @VisibleForTesting
  public static void reset() {
    TEMPORAL_DEBUG_MODE =
        EnvironmentVariableUtils.readBooleanFlag(EnvironmentVariableNames.TEMPORAL_DEBUG);
  }
}
