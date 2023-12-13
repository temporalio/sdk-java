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

import static io.temporal.internal.common.ProtobufTimeUtils.MAX_SECONDS;
import static io.temporal.internal.common.ProtobufTimeUtils.MIN_SECONDS;
import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ProtobufTimeUtilsTest {

  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(
        // Values with integral milliseconds
        new Object[] {0L, 0, 0L, 0},
        new Object[] {0L, 1_000_000, 0L, 1_000_000},
        new Object[] {0L, 500_000_000, 0L, 500_000_000},
        new Object[] {0L, 999_000_000, 0L, 999_000_000},
        new Object[] {123L, 456_000_000, 123L, 456_000_000},
        new Object[] {0L, -1_000_000, 0L, -1_000_000},
        new Object[] {0L, -500_000_000, 0L, -500_000_000},
        new Object[] {0L, -999_000_000, 0L, -999_000_000},
        new Object[] {-123L, -456_000_000, -123L, -456_000_000},

        // Values with fractional milliseconds
        new Object[] {0L, 123_000_001, 0L, 123_000_000},
        new Object[] {0L, 123_100_000, 0L, 123_000_000},
        new Object[] {0L, 123_499_999, 0L, 123_000_000},
        new Object[] {0L, 123_500_000, 0L, 123_000_000},
        new Object[] {0L, 123_999_999, 0L, 123_000_000},
        new Object[] {0L, -123_000_001, 0L, -123_000_000},
        new Object[] {0L, -123_100_000, 0L, -123_000_000},
        new Object[] {0L, -123_499_999, 0L, -123_000_000},
        new Object[] {0L, -123_500_000, 0L, -123_000_000},
        new Object[] {0L, -123_999_999, 0L, -123_000_000},

        // Extremely large values
        new Object[] {MAX_SECONDS, 0, MAX_SECONDS, 0},
        new Object[] {MAX_SECONDS, 999_000_000, MAX_SECONDS, 999_000_000},
        new Object[] {MAX_SECONDS, 999_999_999, MAX_SECONDS, 999_000_000},
        new Object[] {Long.MAX_VALUE, 0, MAX_SECONDS, 0},
        new Object[] {Long.MAX_VALUE, 999_000_000, MAX_SECONDS, 999_000_000},
        new Object[] {Long.MAX_VALUE, 999_999_999, MAX_SECONDS, 999_000_000},
        new Object[] {MIN_SECONDS, 0, MIN_SECONDS, 0},
        new Object[] {MIN_SECONDS, -999_000_000, MIN_SECONDS, -999_000_000},
        new Object[] {MIN_SECONDS, -999_999_999, MIN_SECONDS, -999_000_000},
        new Object[] {Long.MIN_VALUE, 0, MIN_SECONDS, 0},
        new Object[] {Long.MIN_VALUE, -999_000_000, MIN_SECONDS, -999_000_000},
        new Object[] {Long.MIN_VALUE, -999_999_999, MIN_SECONDS, -999_000_000});
  }

  private final long inputSeconds;
  private final int inputNanos;
  private final long outputSeconds;
  private final int outputNanos;

  public ProtobufTimeUtilsTest(
      long inputSeconds, int inputNanos, long outputSeconds, int outputNanos) {
    this.inputSeconds = inputSeconds;
    this.inputNanos = inputNanos;
    this.outputSeconds = outputSeconds;
    this.outputNanos = outputNanos;
  }

  @Test
  public void toJavaDuration() {
    final Duration actual = ProtobufTimeUtils.toJavaDuration(makeProto(inputSeconds, inputNanos));
    final Duration expect = makeJava(outputSeconds, outputNanos);
    assertEquals(expect, actual);
  }

  @Test
  public void toProtoDuration() {
    final com.google.protobuf.Duration actual =
        ProtobufTimeUtils.toProtoDuration(makeJava(inputSeconds, inputNanos));
    final com.google.protobuf.Duration expect = makeProto(outputSeconds, outputNanos);
    assertEquals(expect, actual);
  }

  private static com.google.protobuf.Duration makeProto(long seconds, int nanos) {
    return com.google.protobuf.Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Duration makeJava(long seconds, int nanos) {
    final long saturatedSeconds = Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, seconds));
    return Duration.ofSeconds(saturatedSeconds, nanos);
  }
}
