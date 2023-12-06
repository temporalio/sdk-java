package io.temporal.internal.common;

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

        // Values with fractional milliseconds
        new Object[] {0L, 123_000_001, 0L, 123_000_000},
        new Object[] {0L, 123_100_000, 0L, 123_000_000},
        new Object[] {0L, 123_499_999, 0L, 123_000_000},
        new Object[] {0L, 123_500_000, 0L, 123_000_000},
        new Object[] {0L, 123_999_999, 0L, 123_000_000});
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
    final Duration output =
        ProtobufTimeUtils.toJavaDuration(
            com.google.protobuf.Duration.newBuilder()
                .setSeconds(inputSeconds)
                .setNanos(inputNanos)
                .build());
    assertEquals(outputSeconds, output.getSeconds());
    assertEquals(outputNanos, output.getNano());
  }

  @Test
  public void toProtoDuration() {
    final com.google.protobuf.Duration output =
        ProtobufTimeUtils.toProtoDuration(Duration.ofSeconds(inputSeconds, inputNanos));
    assertEquals(outputSeconds, output.getSeconds());
    assertEquals(outputNanos, output.getNanos());
  }
}
