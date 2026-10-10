package io.temporal.internal.common;

import com.uber.m3.tally.Scope;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.serviceclient.MetricsTag;
import java.util.Collections;

/**
 * Builds the failure a worker reports instead of a completion that exceeds the namespace's payload
 * error limits. The failure is retryable, so that a corrected workflow or activity can be
 * redeployed and continue.
 */
public final class PayloadsTooLargeFailure {
  private PayloadsTooLargeFailure() {}

  public static ApplicationFailure of(PayloadLimitViolation violation) {
    ApplicationFailure failure =
        ApplicationFailure.newBuilder()
            .setMessage(violation.getMessage())
            .setType(PayloadLimitViolationException.FAILURE_TYPE)
            .build();
    // The worker's stack trace says nothing about the oversized payload, so it is not sent.
    failure.setStackTrace(new StackTraceElement[0]);
    return failure;
  }

  /** Counts one task failed for exceeding the payload error limits on {@code counter}. */
  public static void count(Scope metricsScope, String counter) {
    metricsScope
        .tagged(
            Collections.singletonMap(
                MetricsTag.TASK_FAILURE_TYPE, MetricsTag.TASK_FAILURE_VALUE_PAYLOADS_TOO_LARGE))
        .counter(counter)
        .inc(1);
  }
}
