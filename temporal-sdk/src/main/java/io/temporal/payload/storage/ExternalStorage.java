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
  private final @Nullable StorageDriverSelector driverSelector;
  private final @Nullable Integer payloadSizeThreshold;

  private ExternalStorage(
      @Nonnull List<StorageDriver> drivers,
      @Nullable StorageDriverSelector driverSelector,
      @Nullable Integer payloadSizeThreshold) {
    this.drivers = Collections.unmodifiableList(new ArrayList<>(drivers));
    this.driverSelector = driverSelector;
    this.payloadSizeThreshold = payloadSizeThreshold;
  }

  @Nonnull
  public List<StorageDriver> getDrivers() {
    return drivers;
  }

  @Nullable
  public StorageDriverSelector getDriverSelector() {
    return driverSelector;
  }

  /**
   * Minimum payload size in bytes before external storage is considered; {@code null} stores all.
   */
  @Nullable
  public Integer getPayloadSizeThreshold() {
    return payloadSizeThreshold;
  }

  public static final class Builder {
    private List<StorageDriver> drivers = Collections.emptyList();
    private StorageDriverSelector driverSelector;
    private Integer payloadSizeThreshold = DEFAULT_PAYLOAD_SIZE_THRESHOLD;

    private Builder() {}

    /** At least one driver is required. When more than one is set, a selector is also required. */
    public Builder setDrivers(@Nonnull List<StorageDriver> drivers) {
      this.drivers = Objects.requireNonNull(drivers, "drivers");
      return this;
    }

    public Builder setDriverSelector(@Nullable StorageDriverSelector driverSelector) {
      this.driverSelector = driverSelector;
      return this;
    }

    /** Defaults to 256 KiB. Set to {@code null} to store all payloads. */
    public Builder setPayloadSizeThreshold(@Nullable Integer payloadSizeThreshold) {
      this.payloadSizeThreshold = payloadSizeThreshold;
      return this;
    }

    public ExternalStorage build() {
      Preconditions.checkArgument(!drivers.isEmpty(), "At least one driver must be provided");
      Preconditions.checkArgument(
          payloadSizeThreshold == null || payloadSizeThreshold >= 0,
          "payloadSizeThreshold must be greater than or equal to zero");
      Set<String> names = new HashSet<>();
      for (StorageDriver driver : drivers) {
        String name = driver.getName();
        Preconditions.checkArgument(
            names.add(name), "Multiple drivers registered with name '%s'", name);
      }
      Preconditions.checkArgument(
          drivers.size() == 1 || driverSelector != null,
          "driverSelector must be specified when more than one driver is registered");
      return new ExternalStorage(drivers, driverSelector, payloadSizeThreshold);
    }
  }
}
