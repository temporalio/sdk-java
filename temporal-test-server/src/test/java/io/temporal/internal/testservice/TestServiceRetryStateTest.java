package io.temporal.internal.testservice;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.enums.v1.RetryState;
import java.time.Duration;
import java.util.Optional;
import org.junit.Test;

public class TestServiceRetryStateTest {

  private static final RetryPolicy RETRY_POLICY =
      RetryPolicy.newBuilder()
          .setInitialInterval(Durations.fromSeconds(10))
          .setBackoffCoefficient(1.0)
          .build();

  @Test
  public void expirationOnWholeSecondTimesOut() {
    // A deadline with a zero nanos field must still be honored.
    Timestamp now = Timestamps.fromSeconds(1_000);
    Timestamp expiration = Timestamps.fromSeconds(1_005);
    TestServiceRetryState state = new TestServiceRetryState(RETRY_POLICY, expiration);

    TestServiceRetryState.BackoffInterval backoff =
        state.getBackoffIntervalInSeconds(Optional.empty(), now, Optional.empty());

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, backoff.getRetryState());
  }

  @Test
  public void expirationWithSubsecondTimesOut() {
    Timestamp now = Timestamps.fromMillis(1_000_500);
    Timestamp expiration = Timestamps.fromMillis(1_005_500);
    TestServiceRetryState state = new TestServiceRetryState(RETRY_POLICY, expiration);

    TestServiceRetryState.BackoffInterval backoff =
        state.getBackoffIntervalInSeconds(Optional.empty(), now, Optional.empty());

    assertEquals(RetryState.RETRY_STATE_TIMEOUT, backoff.getRetryState());
  }

  @Test
  public void unsetExpirationNeverTimesOut() {
    Timestamp now = Timestamps.fromSeconds(1_000);
    TestServiceRetryState state =
        new TestServiceRetryState(RETRY_POLICY, Timestamp.getDefaultInstance());

    TestServiceRetryState.BackoffInterval backoff =
        state.getBackoffIntervalInSeconds(Optional.empty(), now, Optional.empty());

    assertEquals(RetryState.RETRY_STATE_IN_PROGRESS, backoff.getRetryState());
    assertEquals(Duration.ofSeconds(10), backoff.getInterval());
  }

  @Test
  public void retryWithinExpirationProceeds() {
    Timestamp now = Timestamps.fromSeconds(1_000);
    Timestamp expiration = Timestamps.fromSeconds(1_060);
    TestServiceRetryState state = new TestServiceRetryState(RETRY_POLICY, expiration);

    TestServiceRetryState.BackoffInterval backoff =
        state.getBackoffIntervalInSeconds(Optional.empty(), now, Optional.empty());

    assertEquals(RetryState.RETRY_STATE_IN_PROGRESS, backoff.getRetryState());
    assertEquals(Duration.ofSeconds(10), backoff.getInterval());
  }
}
