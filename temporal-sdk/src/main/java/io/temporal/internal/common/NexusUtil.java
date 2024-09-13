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

package io.temporal.internal.common;

import java.time.Duration;

public class NexusUtil {
  public static Duration parseRequestTimeout(String timeout) {
    try {
      if (timeout.endsWith("m")) {
        return Duration.ofMillis(
            Math.round(
                10e3 * Double.parseDouble(timeout.substring(0, timeout.length() - 1)) / 60.0));
      } else if (timeout.endsWith("s")) {
        return Duration.ofMillis(
            Math.round(10e3 * Double.parseDouble(timeout.substring(0, timeout.length() - 1))));
      } else if (timeout.endsWith("ms")) {
        return Duration.ofMillis(
            Math.round(Double.parseDouble(timeout.substring(0, timeout.length() - 2))));
      } else {
        throw new IllegalArgumentException("Invalid timeout format: " + timeout);
      }
    } catch (NumberFormatException | NullPointerException e) {
      throw new IllegalArgumentException("Invalid timeout format: " + timeout);
    }
  }

  private NexusUtil() {}
}
