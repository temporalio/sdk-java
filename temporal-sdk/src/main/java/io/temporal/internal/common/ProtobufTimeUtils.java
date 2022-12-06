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

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.time.Duration;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ProtobufTimeUtils {
  @Nonnull
  public static Duration toJavaDuration(com.google.protobuf.Duration d) {
    // TODO we should refactor an implicit conversion of empty values into ZERO and rename the
    // current method into toJavaDurationSafe, toJavaDurationOrDefault or something like that
    if (d == null) {
      return Duration.ZERO;
    }

    return Duration.ofMillis(Durations.toMillis(d));
  }

  public static com.google.protobuf.Duration toProtoDuration(Duration d) {
    // TODO we should refactor an implicit conversion of empty values into ZERO and rename the
    // current method into toJavaDurationSafe, toJavaDurationOrDefault or something like that
    if (d == null) {
      return Durations.ZERO;
    }

    return Durations.fromMillis(d.toMillis());
  }

  public static com.google.protobuf.Timestamp getCurrentProtoTime() {
    return Timestamps.fromMillis(System.currentTimeMillis());
  }

  public static com.uber.m3.util.Duration toM3Duration(Timestamp to, Timestamp from) {
    return com.uber.m3.util.Duration.ofMillis(Timestamps.toMillis(to) - Timestamps.toMillis(from));
  }

  public static com.uber.m3.util.Duration toM3DurationSinceNow(Timestamp t) {
    return com.uber.m3.util.Duration.ofMillis(System.currentTimeMillis() - Timestamps.toMillis(t));
  }

  public static @Nullable Instant toJavaInstant(@Nullable com.google.protobuf.Timestamp t) {
    if (t == null) {
      return null;
    }

    return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
  }

  public static @Nullable com.google.protobuf.Timestamp toProtoTimestamp(@Nullable Instant t) {
    if (t == null) {
      return null;
    }

    return Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
  }
}
