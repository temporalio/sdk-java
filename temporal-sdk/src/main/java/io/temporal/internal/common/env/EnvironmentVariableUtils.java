/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
