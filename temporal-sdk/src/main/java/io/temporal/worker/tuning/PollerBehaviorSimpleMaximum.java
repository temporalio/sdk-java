package io.temporal.worker.tuning;

/**
 * A poller behavior that will attempt to poll as long as a slot is available, up to the provided
 * maximum. Cannot be less than two for workflow tasks, or one for other tasks.
 */
public class PollerBehaviorSimpleMaximum implements PollerBehavior {
  private final int maxConcurrentTaskPollers;

  /**
   * Creates a new PollerBehaviorSimpleMaximum with the specified maximum number of concurrent task
   * pollers.
   *
   * @param maxConcurrentTaskPollers Maximum number of concurrent task pollers.
   */
  public PollerBehaviorSimpleMaximum(int maxConcurrentTaskPollers) {
    if (maxConcurrentTaskPollers < 1) {
      throw new IllegalArgumentException("maxConcurrentTaskPollers must be at least 1");
    }
    this.maxConcurrentTaskPollers = maxConcurrentTaskPollers;
  }

  /**
   * Gets the maximum number of concurrent task pollers.
   *
   * @return Maximum number of concurrent task pollers.
   */
  public int getMaxConcurrentTaskPollers() {
    return maxConcurrentTaskPollers;
  }
}
