package io.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class ActivityWorkflow implements TestWorkflowStringArg {
  private final TestActivityArgs activity =
      Workflow.newActivityStub(
          TestActivityArgs.class,
          ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(2)).build());

  @Override
  public int execute(String input) {
    try {
      return activity.execute(input, true);
    } catch (ActivityFailure e) {
      throw e;
    }
  }
}
