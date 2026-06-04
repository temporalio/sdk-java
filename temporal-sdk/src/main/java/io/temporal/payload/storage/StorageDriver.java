package io.temporal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Stores and retrieves payloads in an external storage system. */
@Experimental
public interface StorageDriver {
  /**
   * Name of this driver instance, unique among the drivers registered in a single {@link
   * ExternalStorage}. Used as the routing key recorded in a stored payload's reference and resolved
   * back to this driver on retrieval.
   */
  @Nonnull
  String getName();

  /**
   * Stable, implementation-level identifier for this driver, the same across all instances of the
   * driver class and ideally across SDKs (e.g. {@code "aws.s3driver"}). Used for metrics and worker
   * heartbeat reporting.
   */
  @Nonnull
  default String getType() {
    return getClass().getName();
  }

  /**
   * Stores {@code payloads} and returns one {@link StorageDriverClaim} per payload, in the same
   * order. The returned list must be the same length as {@code payloads}.
   */
  @Nonnull
  CompletableFuture<List<StorageDriverClaim>> store(
      @Nonnull StorageDriverStoreContext context, @Nonnull List<Payload> payloads);

  /**
   * Retrieves the payloads identified by {@code claims} and returns one {@link Payload} per claim,
   * in the same order. The returned list must be the same length as {@code claims}.
   */
  @Nonnull
  CompletableFuture<List<Payload>> retrieve(
      @Nonnull StorageDriverRetrieveContext context, @Nonnull List<StorageDriverClaim> claims);
}
