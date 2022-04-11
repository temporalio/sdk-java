/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.common;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.internal.common.env.EnvironmentVariablesProvider;
import io.temporal.internal.common.env.SystemEnvironmentVariablesProvider;

public class DebugModeUtils {
  private static boolean TEMPORAL_DEBUG_MODE =
      readTemporalDebugMode(SystemEnvironmentVariablesProvider.INSTANCE);

  public static boolean isTemporalDebugModeOn() {
    return TEMPORAL_DEBUG_MODE;
  }

  private static boolean readTemporalDebugMode(EnvironmentVariablesProvider envProvider) {
    String temporalDebugValue = envProvider.getenv("TEMPORAL_DEBUG");
    if (temporalDebugValue == null) {
      return false;
    }
    temporalDebugValue = temporalDebugValue.trim();
    return (!Boolean.FALSE.toString().equalsIgnoreCase(temporalDebugValue)
        && !"0".equals(temporalDebugValue));
  }

  @VisibleForTesting
  public static void override(boolean debugMode) {
    TEMPORAL_DEBUG_MODE = debugMode;
  }

  @VisibleForTesting
  public static void reset() {
    TEMPORAL_DEBUG_MODE = readTemporalDebugMode(SystemEnvironmentVariablesProvider.INSTANCE);
  }
}
