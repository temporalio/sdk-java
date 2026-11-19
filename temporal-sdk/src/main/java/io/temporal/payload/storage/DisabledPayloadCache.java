package io.temporal.payload.storage;

import io.temporal.api.common.v1.Payload;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** The value returned by {@link PayloadCache#disabled()}. */
final class DisabledPayloadCache implements PayloadCache {
  static final DisabledPayloadCache INSTANCE = new DisabledPayloadCache();

  private DisabledPayloadCache() {}

  @Nonnull
  @Override
  public CompletableFuture<Optional<Payload>> get(@Nonnull String key) {
    return CompletableFuture.completedFuture(Optional.empty());
  }

  @Nonnull
  @Override
  public CompletableFuture<Boolean> set(@Nonnull String key, @Nonnull Payload payload) {
    return CompletableFuture.completedFuture(false);
  }

  @Override
  public String toString() {
    return "PayloadCache.disabled()";
  }
}
