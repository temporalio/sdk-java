package io.temporal.workflow.shared;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.activityTests.ActivityThrowingApplicationFailureTest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ApplicationFailureActivity implements TestActivities.TestActivity4 {
  public static final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

  @Override
  public void execute(String testName, boolean retryable) {
    invocations.computeIfAbsent(testName, k -> new AtomicInteger()).incrementAndGet();
    if (retryable) {
      throw ApplicationFailure.newFailure(
          "Simulate retryable failure.",
          ActivityThrowingApplicationFailureTest.FAILURE_TYPE,
          ActivityThrowingApplicationFailureTest.FAILURE_TYPE);
    }
    throw ApplicationFailure.newNonRetryableFailure(
        "Simulate non-retryable failure.",
        ActivityThrowingApplicationFailureTest.FAILURE_TYPE,
        ActivityThrowingApplicationFailureTest.FAILURE_TYPE);
  }
}
