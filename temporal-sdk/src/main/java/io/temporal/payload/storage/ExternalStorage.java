package io.temporal.payload.storage;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Configuration for offloading large payloads to external storage. */
@Experimental
public final class ExternalStorage {
  private static final int DEFAULT_PAYLOAD_SIZE_THRESHOLD = 256 * 1024;

  public static Builder newBuilder() {
    return new Builder();
  }

  private final @Nonnull List<StorageDriver> drivers;
  private final @Nonnull StorageDriverSelector driverSelector;
  private final int payloadSizeThreshold;

  private ExternalStorage(
      @Nonnull List<StorageDriver> drivers,
      @Nonnull StorageDriverSelector driverSelector,
      int payloadSizeThreshold) {
    this.drivers = Collections.unmodifiableList(new ArrayList<>(drivers));
    this.driverSelector = driverSelector;
    this.payloadSizeThreshold = payloadSizeThreshold;
  }

  @Nonnull
  public List<StorageDriver> getDrivers() {
    return drivers;
  }

  @Nonnull
  public StorageDriverSelector getDriverSelector() {
    return driverSelector;
  }

  /**
   * Minimum payload size in bytes before external storage is considered. {@code 0} stores all
   * payloads. Defaults to 256 KiB.
   */
  public int getPayloadSizeThreshold() {
    return payloadSizeThreshold;
  }

  public static final class Builder {
    private List<StorageDriver> drivers = Collections.emptyList();
    private StorageDriverSelector driverSelector;
    private int payloadSizeThreshold = DEFAULT_PAYLOAD_SIZE_THRESHOLD;

    private Builder() {}

    /** At least one driver is required. When more than one is set, a selector is also required. */
    public Builder setDrivers(@Nonnull List<StorageDriver> drivers) {
      this.drivers = Objects.requireNonNull(drivers, "drivers");
      return this;
    }

    /** Convenience for registering a single driver; no selector is needed in this case. */
    public Builder setDriver(@Nonnull StorageDriver driver) {
      return setDrivers(Collections.singletonList(Objects.requireNonNull(driver, "driver")));
    }

    /** Required when more than one driver is registered; with a single driver it is optional. */
    public Builder setDriverSelector(@Nullable StorageDriverSelector driverSelector) {
      this.driverSelector = driverSelector;
      return this;
    }

    /** Set to {@code 0} to store all payloads. Defaults to 256 KiB. */
    public Builder setPayloadSizeThreshold(int payloadSizeThreshold) {
      this.payloadSizeThreshold = payloadSizeThreshold;
      return this;
    }

    public ExternalStorage build() {
      Preconditions.checkState(!drivers.isEmpty(), "At least one driver must be provided");
      Preconditions.checkState(
          payloadSizeThreshold >= 0, "payloadSizeThreshold must be greater than or equal to zero");
      Set<String> names = new HashSet<>();
      for (StorageDriver driver : drivers) {
        String name = driver.getName();
        Preconditions.checkState(
            names.add(name), "Multiple drivers registered with name '%s'", name);
      }
      Preconditions.checkState(
          drivers.size() == 1 || driverSelector != null,
          "driverSelector must be specified when more than one driver is registered");
      StorageDriverSelector selector = driverSelector;
      if (selector == null) {
        StorageDriver driver = drivers.get(0);
        selector = (context, payload) -> driver;
      }
      return new ExternalStorage(drivers, selector, payloadSizeThreshold);
    }
  }
}
