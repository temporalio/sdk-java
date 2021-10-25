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

import com.google.common.base.Defaults;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.Objects;

public final class OptionsUtils {

  public static final Duration DEFAULT_TASK_START_TO_CLOSE_TIMEOUT = Duration.ofSeconds(10);
  public static final float SECOND = 1000f;
  public static final byte[] EMPTY_BLOB = new byte[0];

  public static ByteString toByteString(byte[] value) {
    if (value == null) {
      return ByteString.EMPTY;
    }
    return ByteString.copyFrom(value);
  }

  public static byte[] safeGet(byte[] value) {
    if (value == null) {
      return EMPTY_BLOB;
    }
    return value;
  }

  public static String safeGet(String value) {
    if (value == null) {
      return "";
    }
    return value;
  }

  public static <G> G merge(G value, G overrideValueIfNotDefault, Class<G> type) {
    G defaultValue = Defaults.defaultValue(type);
    if (!Objects.equals(defaultValue, overrideValueIfNotDefault)) {
      return overrideValueIfNotDefault;
    }
    if (type.equals(String.class)) {
      return ((String) value).isEmpty() ? null : value;
    }
    return value;
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

  public static String[] merge(String[] fromAnnotation, String[] fromOptions) {
    if (fromOptions != null) {
      return fromOptions;
    }
    return fromAnnotation;
  }

  /**
   * Convert milliseconds to seconds rounding up. Used by timers to ensure that they never fire
   * earlier than requested.
   */
  public static int roundUpToSeconds(Duration duration, Duration defaultValue) {
    if (duration == null) {
      return roundUpToSeconds(defaultValue);
    }
    return roundUpToSeconds(duration);
  }

  /**
   * Round durations to seconds rounding up. As all timeouts and timers resolution is in seconds
   * ensures that nothing times out or fires before the requested time.
   */
  public static int roundUpToSeconds(Duration duration) {
    if (duration == null) {
      return 0;
    }
    return (int) (Math.ceil(duration.toMillis() / SECOND));
  }

  /** Prohibits instantiation. */
  private OptionsUtils() {}
}
