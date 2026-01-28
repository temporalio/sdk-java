package io.temporal.internal.common;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;
import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import org.junit.Test;

public class RetryOptionsUtilsTest {
  @Test
  public void buildRetryOptions() {
    Duration initialInterval = Duration.ofSeconds(2);
    Duration maxInterval = Duration.ofSeconds(5);
    RetryPolicy retryPolicy1 =
        RetryPolicy.newBuilder()
            .setInitialInterval(ProtobufTimeUtils.toProtoDuration(initialInterval))
            .setMaximumInterval(ProtobufTimeUtils.toProtoDuration(maxInterval))
            .setMaximumAttempts(5)
            .setBackoffCoefficient(2)
            .addNonRetryableErrorTypes(IllegalStateException.class.getName())
            .build();

    RetryOptions retryOptions = RetryOptionsUtils.toRetryOptions(retryPolicy1);
    assertEquals(initialInterval, retryOptions.getInitialInterval());
    assertEquals(maxInterval, retryOptions.getMaximumInterval());
    assertEquals(5, retryOptions.getMaximumAttempts());
    assertEquals(2, retryOptions.getBackoffCoefficient(), 0.001);
    assertEquals(IllegalStateException.class.getName(), retryOptions.getDoNotRetry()[0]);

    assertEquals(
        retryPolicy1.getInitialInterval().getSeconds(),
        retryOptions.getInitialInterval().getSeconds());
    assertEquals(
        retryPolicy1.getMaximumInterval().getSeconds(),
        retryOptions.getMaximumInterval().getSeconds());
    assertEquals(retryPolicy1.getMaximumAttempts(), retryOptions.getMaximumAttempts());
    assertEquals(retryPolicy1.getBackoffCoefficient(), retryOptions.getBackoffCoefficient(), 0.001);
    assertEquals(retryPolicy1.getNonRetryableErrorTypes(0), retryOptions.getDoNotRetry()[0]);

    RetryPolicy retryPolicy2 = toRetryPolicy(retryOptions).build();

    assertEquals(
        retryPolicy2.getInitialInterval().getSeconds(),
        retryOptions.getInitialInterval().getSeconds());
    assertEquals(
        retryPolicy2.getMaximumInterval().getSeconds(),
        retryOptions.getMaximumInterval().getSeconds());
    assertEquals(retryPolicy2.getMaximumAttempts(), retryOptions.getMaximumAttempts());
    assertEquals(retryPolicy2.getBackoffCoefficient(), retryOptions.getBackoffCoefficient(), 0.001);
    assertEquals(retryPolicy2.getNonRetryableErrorTypes(0), retryOptions.getDoNotRetry()[0]);
  }
}
