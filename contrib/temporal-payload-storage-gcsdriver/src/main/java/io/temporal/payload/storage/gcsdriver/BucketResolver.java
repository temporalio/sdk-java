package io.temporal.payload.storage.gcsdriver;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.payload.storage.StorageDriverStoreContext;
import javax.annotation.Nonnull;

/**
 * Resolves the target GCS bucket for a payload. Use {@link
 * GcsStorageDriver.Builder#setBucket(String)} for a fixed bucket, or supply a resolver via {@link
 * GcsStorageDriver.Builder#setBucketResolver(BucketResolver)} to choose a bucket per payload.
 */
@Experimental
@FunctionalInterface
public interface BucketResolver {
  @Nonnull
  String resolveBucket(@Nonnull StorageDriverStoreContext context, @Nonnull Payload payload);
}
