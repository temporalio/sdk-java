package io.temporal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Chooses which {@link StorageDriver} stores a given payload. */
@Experimental
@FunctionalInterface
public interface StorageDriverSelector {
  /**
   * Returns the driver to store {@code payload}, which must be one of the drivers registered in the
   * {@link ExternalStorage}, or {@code null} to leave the payload stored inline.
   */
  @Nullable
  StorageDriver selectDriver(@Nonnull StorageDriverStoreContext context, @Nonnull Payload payload);
}
