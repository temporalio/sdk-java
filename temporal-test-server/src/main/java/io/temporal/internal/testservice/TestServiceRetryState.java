package io.temporal.internal.testservice;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

final class TestServiceRetryState {

  static class BackoffInterval {
    private final Duration interval;
    private final RetryState retryState;

    BackoffInterval(Duration interval) {
      this.interval = interval;
      this.retryState = RetryState.RETRY_STATE_IN_PROGRESS;
    }

    BackoffInterval(RetryState retryState) {
      this.interval = Duration.ofMillis(-1000);
      this.retryState = retryState;
    }

    public Duration getInterval() {
      return interval;
    }

    public RetryState getRetryState() {
      return retryState;
    }
  }

  private final RetryPolicy retryPolicy;
  private final Timestamp expirationTime;
  private final int attempt;
  private final Optional<Failure> lastFailure;

  TestServiceRetryState(RetryPolicy retryPolicy, Timestamp expirationTime) {
    this(validateAndOverrideRetryPolicy(retryPolicy), expirationTime, 1, Optional.empty());
  }

  private TestServiceRetryState(
      RetryPolicy retryPolicy,
      Timestamp expirationTime,
      int attempt,
      Optional<Failure> lastFailure) {
    this.retryPolicy = retryPolicy;
    this.expirationTime =
        Timestamps.toMillis(expirationTime) == 0 ? Timestamps.MAX_VALUE : expirationTime;
    this.attempt = attempt;
    this.lastFailure = lastFailure;
  }

  RetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  Timestamp getExpirationTime() {
    return expirationTime;
  }

  int getAttempt() {
    return attempt;
  }

  public Optional<Failure> getPreviousRunFailure() {
    return lastFailure;
  }

  TestServiceRetryState getNextAttempt(Optional<Failure> failure) {
    return new TestServiceRetryState(retryPolicy, expirationTime, attempt + 1, failure);
  }

  BackoffInterval getBackoffIntervalInSeconds(
      Optional<String> errorType, Timestamp currentTime, Optional<Duration> nextRetryDelay) {
    RetryPolicy retryPolicy = getRetryPolicy();
    // check if error is non-retryable
    List<String> nonRetryableErrorTypes = retryPolicy.getNonRetryableErrorTypesList();
    if (errorType.isPresent()) {
      String type = errorType.get();
      for (String err : nonRetryableErrorTypes) {
        if (type.equals(err)) {
          return new BackoffInterval(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE);
        }
      }
    }
    Timestamp expirationTime = getExpirationTime();
    if (retryPolicy.getMaximumAttempts() == 0 && Timestamps.toMillis(expirationTime) == 0) {
      return new BackoffInterval(RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET);
    }

    if (retryPolicy.getMaximumAttempts() > 0 && getAttempt() >= retryPolicy.getMaximumAttempts()) {
      // currAttempt starts from 1.
      // MaximumAttempts is the total attempts, including initial (non-retry) attempt.
      return new BackoffInterval(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED);
    }

    Optional<ApplicationFailureInfo> info = lastFailure.map(Failure::getApplicationFailureInfo);
    Duration backoffDuration;
    if (nextRetryDelay.isPresent()) {
      backoffDuration = nextRetryDelay.get();
    } else {
      long initInterval = Durations.toMillis(retryPolicy.getInitialInterval());
      long nextInterval =
          (long) (initInterval * Math.pow(retryPolicy.getBackoffCoefficient(), getAttempt() - 1));
      long maxInterval = Durations.toMillis(retryPolicy.getMaximumInterval());
      if (nextInterval <= 0) {
        // math.Pow() could overflow
        if (maxInterval > 0) {
          nextInterval = maxInterval;
        }
      }
      if (maxInterval > 0 && nextInterval > maxInterval) {
        // cap next interval to MaxInterval
        nextInterval = maxInterval;
      } else if (nextInterval <= 0) {
        return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
      }
      backoffDuration = Duration.ofMillis(nextInterval);
    }

    Timestamp nextScheduleTime =
        Timestamps.add(currentTime, ProtobufTimeUtils.toProtoDuration(backoffDuration));
    if (expirationTime.getNanos() != 0
        && Timestamps.compare(nextScheduleTime, expirationTime) > 0) {
      return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
    }
    return new BackoffInterval(backoffDuration);
  }

  static RetryPolicy validateAndOverrideRetryPolicy(RetryPolicy p) {
    RetryPolicy.Builder policy = p.toBuilder();
    if (Durations.compare(policy.getInitialInterval(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("InitialIntervalInSeconds must be greater than 0 on retry policy.")
          .asRuntimeException();
    }
    if (Durations.compare(policy.getInitialInterval(), Durations.ZERO) == 0) {
      policy.setInitialInterval(Durations.fromSeconds(1));
    }
    if (policy.getBackoffCoefficient() != 0 && policy.getBackoffCoefficient() < 1) {
      throw Status.INVALID_ARGUMENT
          .withDescription("BackoffCoefficient cannot be less than 1 on retry policy.")
          .asRuntimeException();
    }
    if (policy.getBackoffCoefficient() == 0) {
      policy.setBackoffCoefficient(2d);
    }
    if (Durations.compare(policy.getMaximumInterval(), Durations.ZERO) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("MaximumIntervalInSeconds cannot be less than 0 on retry policy.")
          .asRuntimeException();
    }
    if (Durations.compare(policy.getMaximumInterval(), Durations.ZERO) > 0
        && Durations.compare(policy.getMaximumInterval(), policy.getInitialInterval()) < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "MaximumIntervalInSeconds cannot be less than InitialIntervalInSeconds on retry policy.")
          .asRuntimeException();
    }
    if (policy.getMaximumAttempts() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("MaximumAttempts cannot be less than 0 on retry policy.")
          .asRuntimeException();
    }
    return policy.build();
  }
}
