package io.temporal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * Cache for payloads that external storage uploads and downloads. Keys are opaque strings.
 *
 * <p>Implementations should not throw or complete exceptionally. The SDK treats a failed {@link
 * #get} as a miss and ignores a failed {@link #set}.
 *
 * @see InMemoryPayloadCache
 */
@Experimental
public interface PayloadCache {
  /** Completes with the payload stored under {@code key}, or empty on a miss. */
  @Nonnull
  CompletableFuture<Optional<Payload>> get(@Nonnull String key);

  /** Completes with whether the cache accepted {@code payload}. */
  @Nonnull
  CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload);

  /**
   * Value that explicitly turns payload caching off. Pass it where a {@code PayloadCache} option is
   * accepted. The SDK never calls its methods.
   */
  @Nonnull
  static PayloadCache disabled() {
    return DisabledPayloadCache.INSTANCE;
  }
}
