package io.temporal.payload.storage;

import io.temporal.common.Experimental;

/**
 * Context passed to {@link StorageDriver#retrieve}.
 *
 * <p>Implemented by the SDK and passed to the driver. Driver authors do not implement this in
 * production code, only when constructing instances for their own tests.
 */
@Experimental
public interface StorageDriverRetrieveContext {}
