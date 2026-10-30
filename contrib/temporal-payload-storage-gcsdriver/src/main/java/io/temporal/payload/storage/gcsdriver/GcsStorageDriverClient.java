package io.temporal.payload.storage.gcsdriver;

import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Provider operations used by {@link GcsStorageDriver}. Implementations must support concurrent
 * calls and make a best-effort attempt to cancel underlying work when a returned future is
 * cancelled.
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

  /** Downloads an object's bytes. A missing object must complete exceptionally. */
  @Nonnull
  CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String objectName);
}
