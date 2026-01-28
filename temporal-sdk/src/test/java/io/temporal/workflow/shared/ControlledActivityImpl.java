package io.temporal.workflow.shared;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.Activity;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ControlledActivityImpl implements TestActivities.NoArgsReturnsStringActivity {

  public enum Outcome {
    FAIL,
    SLEEP,
    COMPLETE
  }

  private final List<Outcome> outcomes;
  private final int expectedAttempts;
  private final long secondsToSleep;

  private volatile int lastAttempt = 0;

  public ControlledActivityImpl(List<Outcome> outcomes, int expectedAttempts, long secondsToSleep) {
    this.outcomes = outcomes;
    this.expectedAttempts = expectedAttempts;
    this.secondsToSleep = secondsToSleep;
  }

  @Override
  public String execute() {
    lastAttempt = Activity.getExecutionContext().getInfo().getAttempt();
    Outcome outcome = outcomes.get((lastAttempt - 1) % outcomes.size());
    switch (outcome) {
      case FAIL:
        throw new RuntimeException("intentional failure");
      case SLEEP:
        try {
          Thread.sleep(TimeUnit.SECONDS.toMillis(secondsToSleep));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
        return "done";
      case COMPLETE:
        return "done";
      default:
        throw new IllegalArgumentException("Unexpected outcome: " + outcome);
    }
  }

  public void verifyAttempts() {
    assertEquals(
        "Amount of attempts performed is different from the expected",
        expectedAttempts,
        lastAttempt);
  }

  public int getLastAttempt() {
    return lastAttempt;
  }
}
