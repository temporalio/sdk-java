/*
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

package com.uber.cadence.internal.common;

import com.google.common.base.Defaults;
import java.time.Duration;

public final class OptionsUtils {

  public static final Duration DEFAULT_TASK_START_TO_CLOSE_TIMEOUT = Duration.ofSeconds(10);
  public static final float SECOND = 1000f;

  /** Merges value from annotation and option. Option value takes precedence. */
  public static <G> G merge(G annotation, G options, Class<G> type) {
    G defaultValue = Defaults.defaultValue(type);
    if (defaultValue == null) {
      if (options != null) {
        return options;
      }
    } else if (!defaultValue.equals(options)) {
      return options;
    }
    if (type.equals(String.class)) {
      return ((String) annotation).isEmpty() ? null : annotation;
    }
    return annotation;
  }

  /**
   * Merges value from annotation in seconds with option value as Duration. Option value takes
   * precedence.
   */
  public static Duration merge(long aSeconds, Duration o) {
    if (o != null) {
      return o;
    }
    return aSeconds == 0 ? null : Duration.ofSeconds(aSeconds);
  }

  /**
   * Convert milliseconds to seconds rounding up. Used by timers to ensure that they never fire
   * earlier than requested.
   */
  public static Duration roundUpToSeconds(Duration duration, Duration defaultValue) {
    if (duration == null) {
      return defaultValue;
    }
    return roundUpToSeconds(duration);
  }

  /**
   * Round durations to seconds rounding up. As all timeouts and timers resolution is in seconds
   * ensures that nothing times out or fires before the requested time.
   */
  public static Duration roundUpToSeconds(Duration duration) {
    if (duration == null) {
      return Duration.ZERO;
    }
    Duration result = Duration.ofMillis((long) (Math.ceil(duration.toMillis() / SECOND) * SECOND));
    return result;
  }

  /** Prohibits instantiation. */
  private OptionsUtils() {}
}
