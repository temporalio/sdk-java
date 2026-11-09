package io.temporal.internal.common;

import com.uber.m3.tally.Scope;
import io.temporal.serviceclient.MetricsTag;

public final class MetricsUtils {
  private MetricsUtils() {}

  /** Counts one task failed for exceeding the payload error limits on {@code counter}. */
  public static void countPayloadsTooLargeFailure(Scope metricsScope, String counter) {
    MetricsTag.tagged(
            metricsScope,
            MetricsTag.TASK_FAILURE_TYPE,
            MetricsTag.TASK_FAILURE_VALUE_PAYLOADS_TOO_LARGE)
        .counter(counter)
        .inc(1);
  }
}
