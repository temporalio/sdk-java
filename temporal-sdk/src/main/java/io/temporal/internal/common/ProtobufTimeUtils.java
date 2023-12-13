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
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ProtobufTimeUtils {

  static final long MAX_SECONDS = 315_576_000_000L;
  static final long MIN_SECONDS = -315_576_000_000L;
  static final int MAX_NANOS = 999_999_999;
  static final int MIN_NANOS = -999_999_999;
  static final int MILLIS_PER_NANO = 1_000_000;

  /**
   * Converts a Protobuf Duration to a Java Duration with millisecond precision. Null inputs are
   * treated as zero.
   */
  @Nonnull
  public static Duration toJavaDuration(@Nullable com.google.protobuf.Duration d) {
    if (Objects.isNull(d)) {
      return Duration.ZERO;
    }

    // NB: Durations.toMillis is tempting, but it can throw ArithmeticException if
    // Durations.isValid would return false, e.g. if the proto contains a number of
    // seconds outside [MIN_SECONDS,MAX_SECONDS].  We don't want to throw an exception
    // under any circumstances, so we clip it to the range using saturating arithmetic,
    // since that's probably the best we can do to reflect the user's intent.
    //
    // Additionally, it's worth noting that Durations.toMillis truncates toward zero,
    // just as we do here.  This behavior is correctly documented in the JavaDoc, but
    // the exact wording is confusing: one can easily misread the phrase
    // "rounded ... to the nearest millisecond" as a reference to "round to nearest"
    // a.k.a. grade school rounding, where a fractional value of half or more gets
    // rounded away from zero.  However, a careful reading of the JavaDoc shows that
    // the doc author does not mean "nearest" in that way.
    //
    final long rawSeconds = d.getSeconds();
    final int rawNanos = d.getNanos();
    final long saturatedSeconds = Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, rawSeconds));
    final int saturatedNanos = Math.min(MAX_NANOS, Math.max(MIN_NANOS, rawNanos));
    final int roundedNanos = (saturatedNanos / MILLIS_PER_NANO) * MILLIS_PER_NANO;
    return Duration.ofSeconds(saturatedSeconds, roundedNanos);
  }

  /**
   * Converts a Java Duration to a Protobuf Duration with millisecond precision. Null inputs are
   * treated as zero.
   */
  @Nonnull
  public static com.google.protobuf.Duration toProtoDuration(@Nullable Duration d) {
    if (Objects.isNull(d)) {
      return Durations.ZERO;
    }

    // NB: the overflow behavior is not documented, but Duration.toMillis seems to
    // use saturating arithmetic, which is what we want.
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
