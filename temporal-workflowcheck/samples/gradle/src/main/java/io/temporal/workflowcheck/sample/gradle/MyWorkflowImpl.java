package io.temporal.workflowcheck.sample.gradle;

import java.time.LocalTime;
import io.temporal.failure.ApplicationFailure;

public class MyWorkflowImpl implements MyWorkflow {
  @Override
  public void errorAtNight() {
    // Let's throw an application exception only after 8 PM local time
    if (LocalTime.now().getHour() >= 20) {
      throw ApplicationFailure.newFailure("Can't call this workflow after 8PM", "time-error");
    }
  }
}
