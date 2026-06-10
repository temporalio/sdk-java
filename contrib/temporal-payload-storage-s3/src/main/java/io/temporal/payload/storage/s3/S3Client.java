package io.temporal.payload.storage.s3;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Interface for S3 {@link S3StorageDriver} operations: upload, existence check, and download. */
@Experimental
public interface S3Client {
  /**
   * Uploads {@code data} to the given {@code bucket} and {@code key}, overwriting any existing
   * object at that key. Implementations must be safe to call concurrently for different keys.
   */
  @Nonnull
  CompletableFuture<Void> putObject(
      @Nonnull String bucket, @Nonnull String key, @Nonnull byte[] data);

  /**
   * Reports whether an object exists at the given {@code bucket} and {@code key}. The future
   * completes with {@code false} when the object is absent, and completes exceptionally when
   * existence cannot be determined (e.g. a network or permission failure).
   */
  @Nonnull
  CompletableFuture<Boolean> objectExists(@Nonnull String bucket, @Nonnull String key);

  /**
   * Downloads the bytes stored at the given {@code bucket} and {@code key}. The future completes
   * exceptionally if the object does not exist.
   */
  @Nonnull
  CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String key);

  /**
   * Diagnostic metadata about the client configuration, such as {@code {"client_region":
   * "us-west-2"}}, that the driver appends to error messages. Returns an empty map by default.
   */
  @Nonnull
  default Map<String, String> describe() {
    return Collections.emptyMap();
  }
}
