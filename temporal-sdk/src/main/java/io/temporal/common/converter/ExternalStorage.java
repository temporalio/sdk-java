package io.temporal.common.converter;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration for external storage that offloads large payloads to an external store (e.g., S3)
 * using the Claim Check pattern. Payloads exceeding the {@link #getPayloadSizeThreshold()} are
 * stored externally and replaced with a small {@link StorageDriverClaim} reference token in Event
 * History.
 *
 * <p>External storage is the <b>last stage</b> of the data conversion pipeline, running after
 * {@link io.temporal.payload.codec.PayloadCodec} (compression/encryption):
 *
 * <pre>
 *   PayloadConverter → PayloadCodec → ExternalStorage
 * </pre>
 *
 * <p>This is completely transparent to workflow and activity business logic.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ExternalStorage externalStorage = ExternalStorage.newBuilder()
 *     .addDriver(s3Driver)
 *     .setPayloadSizeThreshold(256 * 1024) // 256 KiB
 *     .build();
 * }</pre>
 *
 * @see StorageDriver
 */
@Experimental
public final class ExternalStorage {

  /** Default payload size threshold: 256 KiB (262144 bytes). */
  public static final int DEFAULT_PAYLOAD_SIZE_THRESHOLD = 256 * 1024;

  private final List<StorageDriver> drivers;
  private final @Nullable StorageDriverSelector driverSelector;
  private final int payloadSizeThreshold;

  private ExternalStorage(
      List<StorageDriver> drivers,
      @Nullable StorageDriverSelector driverSelector,
      int payloadSizeThreshold) {
    this.drivers = Collections.unmodifiableList(new ArrayList<>(drivers));
    this.driverSelector = driverSelector;
    this.payloadSizeThreshold = payloadSizeThreshold;
  }

  /** Creates a new builder for {@link ExternalStorage}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Returns the list of registered storage drivers. */
  @Nonnull
  public List<StorageDriver> getDrivers() {
    return drivers;
  }

  /**
   * Returns the driver selector, or null if only one driver is registered. Required when multiple
   * drivers are configured.
   */
  @Nullable
  public StorageDriverSelector getDriverSelector() {
    return driverSelector;
  }

  /**
   * Returns the payload size threshold in bytes. Payloads with serialized size greater than or
   * equal to this threshold will be offloaded to external storage.
   *
   * <p>Default is {@link #DEFAULT_PAYLOAD_SIZE_THRESHOLD} (256 KiB). Set to 1 to externalize all
   * payloads.
   */
  public int getPayloadSizeThreshold() {
    return payloadSizeThreshold;
  }

  /**
   * Selects the appropriate driver for the given store context. If only one driver is registered,
   * returns that driver. Otherwise, delegates to the configured {@link StorageDriverSelector}.
   *
   * @param context the store context
   * @return the selected driver, or null if the payload should remain inline
   */
  @Nullable
  StorageDriver selectDriver(StorageDriverStoreContext context) {
    if (drivers.size() == 1) {
      return drivers.get(0);
    }
    if (driverSelector != null) {
      return driverSelector.select(context, drivers);
    }
    // Should not happen if builder validates correctly.
    return drivers.get(0);
  }

  /**
   * Finds a driver by name for retrieval operations.
   *
   * @param driverName the driver name stored in the claim
   * @return the matching driver
   * @throws StorageDriverException if no driver with the given name is found
   */
  @Nonnull
  StorageDriver findDriverByName(String driverName) {
    for (StorageDriver driver : drivers) {
      if (driver.name().equals(driverName)) {
        return driver;
      }
    }
    throw new StorageDriverException(
        "No storage driver found with name '"
            + driverName
            + "'. Registered drivers: "
            + driverNames());
  }

  private String driverNames() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < drivers.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(drivers.get(i).name());
    }
    sb.append("]");
    return sb.toString();
  }

  @Override
  public String toString() {
    return "ExternalStorage{"
        + "drivers="
        + driverNames()
        + ", payloadSizeThreshold="
        + payloadSizeThreshold
        + '}';
  }

  /** Builder for {@link ExternalStorage}. */
  public static final class Builder {
    private final List<StorageDriver> drivers = new ArrayList<>();
    private StorageDriverSelector driverSelector;
    private int payloadSizeThreshold = DEFAULT_PAYLOAD_SIZE_THRESHOLD;

    private Builder() {}

    /**
     * Adds a storage driver.
     *
     * @param driver the driver to add
     * @return this builder
     */
    public Builder addDriver(@Nonnull StorageDriver driver) {
      this.drivers.add(Preconditions.checkNotNull(driver, "driver"));
      return this;
    }

    /**
     * Sets the list of storage drivers, replacing any previously added.
     *
     * @param drivers the drivers to use
     * @return this builder
     */
    public Builder setDrivers(@Nonnull List<StorageDriver> drivers) {
      this.drivers.clear();
      this.drivers.addAll(Preconditions.checkNotNull(drivers, "drivers"));
      return this;
    }

    /**
     * Sets the driver selector for choosing which driver stores a payload. Required when multiple
     * drivers are registered.
     *
     * @param driverSelector the selector
     * @return this builder
     */
    public Builder setDriverSelector(@Nullable StorageDriverSelector driverSelector) {
      this.driverSelector = driverSelector;
      return this;
    }

    /**
     * Sets the payload size threshold in bytes. Payloads with serialized size greater than or equal
     * to this value will be offloaded to external storage.
     *
     * <p>Default is {@link #DEFAULT_PAYLOAD_SIZE_THRESHOLD} (256 KiB). Set to 1 to externalize all
     * payloads.
     *
     * @param payloadSizeThreshold threshold in bytes, must be positive
     * @return this builder
     */
    public Builder setPayloadSizeThreshold(int payloadSizeThreshold) {
      Preconditions.checkArgument(
          payloadSizeThreshold > 0,
          "payloadSizeThreshold must be positive, got %s",
          payloadSizeThreshold);
      this.payloadSizeThreshold = payloadSizeThreshold;
      return this;
    }

    /**
     * Builds the {@link ExternalStorage} configuration.
     *
     * @throws IllegalStateException if no drivers are configured or if multiple drivers are
     *     configured without a selector
     */
    public ExternalStorage build() {
      Preconditions.checkState(!drivers.isEmpty(), "At least one StorageDriver must be configured");
      Preconditions.checkState(
          drivers.size() <= 1 || driverSelector != null,
          "A StorageDriverSelector is required when multiple drivers are configured");
      return new ExternalStorage(drivers, driverSelector, payloadSizeThreshold);
    }
  }
}
