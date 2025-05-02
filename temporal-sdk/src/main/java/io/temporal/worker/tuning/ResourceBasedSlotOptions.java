package io.temporal.worker.tuning;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;

/** Options resource-based slot suppliers */
@Experimental
public class ResourceBasedSlotOptions {
  private final int minimumSlots;
  private final int maximumSlots;
  private final Duration rampThrottle;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private int minimumSlots;
    private int maximumSlots;
    private Duration rampThrottle;

    private Builder() {}

    /**
     * @param minimumSlots minimum number of slots that will be issued without any resource checks
     */
    public Builder setMinimumSlots(int minimumSlots) {
      this.minimumSlots = minimumSlots;
      return this;
    }

    /**
     * @param maximumSlots maximum number of slots that will ever be issued
     */
    public Builder setMaximumSlots(int maximumSlots) {
      this.maximumSlots = maximumSlots;
      return this;
    }

    /**
     * @param rampThrottle time to wait between slot issuance. This value matters because how many
     *     resources a task will use cannot be determined ahead of time, and thus the system should
     *     wait to see how much resources are used before issuing more slots.
     */
    public Builder setRampThrottle(Duration rampThrottle) {
      this.rampThrottle = rampThrottle;
      return this;
    }

    public ResourceBasedSlotOptions build() {
      return new ResourceBasedSlotOptions(minimumSlots, maximumSlots, rampThrottle);
    }
  }

  /**
   * @param minimumSlots minimum number of slots that will be issued without any resource checks
   * @param maximumSlots maximum number of slots that will ever be issued
   * @param rampThrottle time to wait between slot issuance. This value matters because how many
   *     resources a task will use cannot be determined ahead of time, and thus the system should
   *     wait to see how much resources are used before issuing more slots.
   */
  private ResourceBasedSlotOptions(int minimumSlots, int maximumSlots, Duration rampThrottle) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ResourceBasedSlotOptions that = (ResourceBasedSlotOptions) o;
    return minimumSlots == that.minimumSlots
        && maximumSlots == that.maximumSlots
        && Objects.equals(rampThrottle, that.rampThrottle);
  }

  @Override
  public int hashCode() {
    return Objects.hash(minimumSlots, maximumSlots, rampThrottle);
  }

  @Override
  public String toString() {
    return "ResourceBasedSlotOptions{"
        + "minimumSlots="
        + minimumSlots
        + ", maximumSlots="
        + maximumSlots
        + ", rampThrottle="
        + rampThrottle
        + '}';
  }
}
