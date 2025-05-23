package io.temporal.worker.tuning;

/**
 * A poller behavior that will automatically scale the number of pollers based on feedback from the
 * server. A slot must be available before beginning polling.
 *
 * <p>If the server does not support autoscaling, then the number of pollers will stay at the
 * initial number of pollers.
 */
public final class PollerBehaviorAutoscaling implements PollerBehavior {
  private final int minConcurrentTaskPollers;
  private final int maxConcurrentTaskPollers;
  private final int initialConcurrentTaskPollers;

  public PollerBehaviorAutoscaling(
      int minConcurrentTaskPollers,
      int maxConcurrentTaskPollers,
      int initialConcurrentTaskPollers) {
    this.minConcurrentTaskPollers = minConcurrentTaskPollers;
    this.maxConcurrentTaskPollers = maxConcurrentTaskPollers;
    this.initialConcurrentTaskPollers = initialConcurrentTaskPollers;
  }

  public int getMinConcurrentTaskPollers() {
    return minConcurrentTaskPollers;
  }

  public int getMaxConcurrentTaskPollers() {
    return maxConcurrentTaskPollers;
  }

  public int getInitialMaxConcurrentTaskPollers() {
    return initialConcurrentTaskPollers;
  }
}
