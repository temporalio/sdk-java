package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverLimiter;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;

final class StorageDriverRetrieveContextImpl implements StorageDriverRetrieveContext {
  private final CancellationToken<CancellationException> cancellationToken;
  private final StorageDriverLimiter<StorageDriverClaim> limiter;

  StorageDriverRetrieveContextImpl(
      CancellationToken<CancellationException> cancellationToken,
      StorageDriverLimiter<StorageDriverClaim> limiter) {
    this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    this.limiter = Objects.requireNonNull(limiter, "limiter");
  }

  @Nonnull
  @Override
  public StorageDriverLimiter<StorageDriverClaim> getLimiter() {
    return limiter;
  }

  @Nonnull
  @Override
  public CancellationToken<CancellationException> getCancellationToken() {
    return cancellationToken;
  }
}
