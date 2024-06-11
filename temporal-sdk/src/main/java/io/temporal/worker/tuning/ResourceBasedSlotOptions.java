package io.temporal.worker.tuning;

import java.time.Duration;

public class ResourceBasedSlotOptions {
  private final int minimumSlots;
  private final int maximumSlots;
  private final Duration rampThrottle;

  public ResourceBasedSlotOptions(int minimumSlots, int maximumSlots, Duration rampThrottle) {
    this.minimumSlots = minimumSlots;
    this.maximumSlots = maximumSlots;
    this.rampThrottle = rampThrottle;
  }

  public int getMinimumSlots() {
    return minimumSlots;
  }

  public int getMaximumSlots() {
    return maximumSlots;
  }

  public Duration getRampThrottle() {
    return rampThrottle;
  }
}
