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

  /**
   * Creates a new PollerBehaviorAutoscaling with the specified parameters.
   *
   * @param minConcurrentTaskPollers Minimum number of concurrent task pollers.
   * @param maxConcurrentTaskPollers Maximum number of concurrent task pollers.
   * @param initialConcurrentTaskPollers Initial number of concurrent task pollers.
   */
  public PollerBehaviorAutoscaling(
      int minConcurrentTaskPollers,
      int maxConcurrentTaskPollers,
      int initialConcurrentTaskPollers) {
    if (minConcurrentTaskPollers < 1) {
      throw new IllegalArgumentException("minConcurrentTaskPollers must be at least 1");
    }
    if (maxConcurrentTaskPollers < minConcurrentTaskPollers) {
      throw new IllegalArgumentException(
          "maxConcurrentTaskPollers must be greater than or equal to minConcurrentTaskPollers");
    }
    if (initialConcurrentTaskPollers < minConcurrentTaskPollers
        || initialConcurrentTaskPollers > maxConcurrentTaskPollers) {
      throw new IllegalArgumentException(
          "initialConcurrentTaskPollers must be between minConcurrentTaskPollers and maxConcurrentTaskPollers");
    }
    this.minConcurrentTaskPollers = minConcurrentTaskPollers;
    this.maxConcurrentTaskPollers = maxConcurrentTaskPollers;
    this.initialConcurrentTaskPollers = initialConcurrentTaskPollers;
  }

  /**
   * Gets the minimum number of concurrent task pollers.
   *
   * @return Minimum number of concurrent task pollers.
   */
  public int getMinConcurrentTaskPollers() {
    return minConcurrentTaskPollers;
  }

  /**
   * Gets the maximum number of concurrent task pollers.
   *
   * @return Maximum number of concurrent task pollers.
   */
  public int getMaxConcurrentTaskPollers() {
    return maxConcurrentTaskPollers;
  }

  /**
   * Gets the initial number of concurrent task pollers.
   *
   * @return Initial number of concurrent task pollers.
   */
  public int getInitialMaxConcurrentTaskPollers() {
    return initialConcurrentTaskPollers;
  }
}
