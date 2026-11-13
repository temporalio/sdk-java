package io.temporal.internal.payload.storage;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.StorageDriverLimiter;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class StorageDriverStoreContextImpl implements StorageDriverStoreContext {
  private final @Nullable StorageDriverTargetInfo target;
  private final CancellationToken<CancellationException> cancellationToken;
  private final StorageDriverLimiter<Payload> limiter;

  StorageDriverStoreContextImpl(
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken,
      StorageDriverLimiter<Payload> limiter) {
    this.target = target;
    this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  @Nonnull
  @Override
  public StorageDriverLimiter<Payload> getLimiter() {
    return limiter;
  }

  @Nullable
  @Override
  public StorageDriverTargetInfo getTarget() {
    return target;
  }

  @Nonnull
  @Override
  public CancellationToken<CancellationException> getCancellationToken() {
    return cancellationToken;
  }
}
