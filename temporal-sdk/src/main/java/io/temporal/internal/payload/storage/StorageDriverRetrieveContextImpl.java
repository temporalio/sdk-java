package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;

final class StorageDriverRetrieveContextImpl implements StorageDriverRetrieveContext {
  private final CancellationToken<CancellationException> cancellationToken;

  StorageDriverRetrieveContextImpl(CancellationToken<CancellationException> cancellationToken) {
    this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
  }

  @Nonnull
  @Override
  public CancellationToken<CancellationException> getCancellationToken() {
    return cancellationToken;
  }
}
