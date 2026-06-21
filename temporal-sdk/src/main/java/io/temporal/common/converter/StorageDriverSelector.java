package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.util.List;

/**
 * Functional interface for selecting which {@link StorageDriver} should be used to store a specific
 * payload. Required when multiple drivers are registered in {@link ExternalStorage}.
 *
 * <p>Drivers not selected for storage remain available for retrieval, which supports migration
 * scenarios (e.g., switching from one S3 bucket to another).
 *
 * @see ExternalStorage
 */
@Experimental
@FunctionalInterface
public interface StorageDriverSelector {

  /**
   * Selects a storage driver for the given store context.
   *
   * @param context context with identity information about the workflow/activity owning the payload
   * @param drivers the list of available drivers
   * @return the selected driver to use for storage, or null to keep the payload inline (not
   *     externalized)
   */
  StorageDriver select(StorageDriverStoreContext context, List<StorageDriver> drivers);
}
