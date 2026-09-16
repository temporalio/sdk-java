package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that the scheduled time reported for each activity retry attempt reflects the retry
 * backoff. Regression test for the test server stamping the retry attempt's scheduled time with the
 * failure time instead of the time the retry is due, which made the first retry look immediate.
 */
public class ActivityRetryScheduledTimeTest {

  private static final Duration INITIAL_INTERVAL = Duration.ofSeconds(10);
  private static final int BACKOFF_COEFFICIENT = 2;
  private static final int ATTEMPTS = 4;

  private final RecordingActivityImpl activity = new RecordingActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestActivityRetryScheduledTime.class)
          .setActivityImplementations(activity)
          .build();

  @Test
  public void retryScheduledTimeFollowsBackoff() {
    // Test server regression test: with a real server the retries take over a minute of wall time.
    assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    workflowStub.execute(testWorkflowRule.getTaskQueue());

    List<Long> scheduled = activity.scheduledTimestamps;
    assertEquals(ATTEMPTS, scheduled.size());
    Duration expected = INITIAL_INTERVAL;
    for (int i = 1; i < scheduled.size(); i++) {
      Duration actual = Duration.ofMillis(scheduled.get(i) - scheduled.get(i - 1));
      // The gap also includes the previous attempt's queueing and failure handling, so allow
      // slack around the backoff.
      Duration lowerBound = expected.minusSeconds(1);
      Duration upperBound = expected.plusSeconds(5);
      assertTrue(
          "attempt "
              + (i + 1)
              + " scheduled "
              + actual
              + " after previous, expected between "
              + lowerBound
              + " and "
              + upperBound,
          actual.compareTo(lowerBound) >= 0 && actual.compareTo(upperBound) <= 0);
      expected = expected.multipliedBy(BACKOFF_COEFFICIENT);
    }
  }

  @ActivityInterface
  public interface RecordingActivity {
    @ActivityMethod
    void run();
  }

  public static class RecordingActivityImpl implements RecordingActivity {
    final List<Long> scheduledTimestamps = new ArrayList<>();

    @Override
    public void run() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      scheduledTimestamps.add(info.getCurrentAttemptScheduledTimestamp());
      if (info.getAttempt() < ATTEMPTS) {
        throw new RuntimeException("retry attempt " + info.getAttempt());
      }
    }
  }

  public static class TestActivityRetryScheduledTime implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setStartToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(INITIAL_INTERVAL)
                      .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                      .setMaximumAttempts(ATTEMPTS)
                      .build())
              .build();
      Workflow.newActivityStub(RecordingActivity.class, options).run();
      return "done";
    }
  }
}
