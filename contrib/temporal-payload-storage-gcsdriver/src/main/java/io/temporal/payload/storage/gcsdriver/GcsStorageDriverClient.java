package io.temporal.payload.storage.gcsdriver;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Provider operations used by {@link GcsStorageDriver}: upload, existence check, and download.
 * Implementations must support concurrent calls and make a best-effort attempt to cancel underlying
 * work when a returned future is cancelled.
 */
@Experimental
public interface GcsStorageDriverClient {
  /**
   * Creates an object only if absent. Success means the object was created or already exists;
   * existing contents must never be replaced.
   */
  @Nonnull
  CompletableFuture<Void> putObjectIfAbsent(
      @Nonnull String bucket, @Nonnull String objectName, @Nonnull byte[] data);

  /**
   * Reports whether an object exists. Completes with {@code false} when the object is absent, and
   * completes exceptionally when existence cannot be determined, such as on a network or permission
   * failure.
   */
  @Nonnull
  CompletableFuture<Boolean> objectExists(@Nonnull String bucket, @Nonnull String objectName);

  /** Downloads an object's bytes. A missing object must complete exceptionally. */
  @Nonnull
  CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String objectName);

  /**
   * Diagnostic metadata about the client configuration, such as {@code {"client_project_id":
   * "my-project"}}, that the driver appends to request failure messages. Returns an empty map by
   * default.
   */
  @Nonnull
  default Map<String, String> describe() {
    return Collections.emptyMap();
  }
}
