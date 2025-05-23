package io.temporal.worker.tuning;

/**
 * A poller behavior that will attempt to poll as long as a slot is available, up to the provided
 * maximum. Cannot be less than two for workflow tasks, or one for other tasks.
 */
public class PollerBehaviorSimpleMaximum implements PollerBehavior {
  private final int maxConcurrentTaskPollers;

  public PollerBehaviorSimpleMaximum(int maxConcurrentTaskPollers) {
    this.maxConcurrentTaskPollers = maxConcurrentTaskPollers;
  }

  public int getMaxConcurrentTaskPollers() {
    return maxConcurrentTaskPollers;
  }
}
