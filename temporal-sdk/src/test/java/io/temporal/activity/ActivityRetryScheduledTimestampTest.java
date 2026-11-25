package io.temporal.activity;

import static org.junit.Assert.assertTrue;

import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;

public class ActivityRetryScheduledTimestampTest {
  private static final Duration INITIAL_INTERVAL = Duration.ofSeconds(10);

  private final AtomicLong firstScheduled = new AtomicLong();
  private final AtomicLong retryScheduled = new AtomicLong();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(RetryWorkflow.class)
          .setActivityImplementations(
              (TestActivities.NoArgsActivity) this::recordScheduledTimestamp)
          .build();

  @Test
  public void retryScheduledTimestampIncludesBackoff() {
    testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class).execute();

    long elapsed = retryScheduled.get() - firstScheduled.get();
    assertTrue(
        "Elapsed time between scheduled attempts: " + elapsed + " ms",
        elapsed >= INITIAL_INTERVAL.toMillis());
  }

  private void recordScheduledTimestamp() {
    ActivityInfo info = Activity.getExecutionContext().getInfo();
    if (info.getAttempt() == 1) {
      firstScheduled.set(info.getCurrentAttemptScheduledTimestamp());
      throw ApplicationFailure.newFailure("Retry this activity", "TestFailure");
    }
    retryScheduled.set(info.getCurrentAttemptScheduledTimestamp());
  }

  public static class RetryWorkflow implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.newActivityStub(
              TestActivities.NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(5))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(INITIAL_INTERVAL)
                          .setMaximumAttempts(2)
                          .build())
                  .build())
          .execute();
    }
  }
}
