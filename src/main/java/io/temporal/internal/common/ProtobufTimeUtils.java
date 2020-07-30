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

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ProtobufTimeUtils {
  public static Duration ToJavaDuration(com.google.protobuf.Duration d) {
    if (d == null) {
      return Duration.ZERO;
    }

    return Duration.ofNanos(Durations.toNanos(d));
  }

  public static com.google.protobuf.Duration ToProtoDuration(Duration d) {
    if (d == null) {
      return Durations.ZERO;
    }

    return Durations.fromNanos(d.toNanos());
  }

  public static com.uber.m3.util.Duration ToM3Duration(Timestamp to, Timestamp from) {
    return com.uber.m3.util.Duration.ofNanos(Timestamps.toNanos(to) - Timestamps.toNanos(from));
  }

  public static com.uber.m3.util.Duration ToM3DurationSinceNow(Timestamp t) {
    long currentNanoTime =
        TimeUnit.NANOSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    return com.uber.m3.util.Duration.ofNanos(currentNanoTime - Timestamps.toNanos(t));
  }
}
