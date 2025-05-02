package io.temporal.common;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class RetryOptionsTest {

  @Test
  public void mergePrefersTheParameter() {
    RetryOptions o1 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .validateBuildWithDefaults();
    RetryOptions o2 =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(2))
            .validateBuildWithDefaults();

    assertEquals(Duration.ofSeconds(2), o1.merge(o2).getInitialInterval());
  }

  @Test(expected = IllegalStateException.class)
  public void maximumIntervalCantBeLessThanInitial() {
    RetryOptions.newBuilder()
        .setInitialInterval(Duration.ofSeconds(5))
        .setMaximumInterval(Duration.ofSeconds(1))
        .validateBuildWithDefaults();
  }
}
