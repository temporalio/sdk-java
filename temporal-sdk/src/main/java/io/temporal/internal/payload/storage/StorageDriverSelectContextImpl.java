package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.StorageDriverSelectContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class StorageDriverSelectContextImpl implements StorageDriverSelectContext {
  private final @Nullable StorageDriverTargetInfo target;
  private final CancellationToken<CancellationException> cancellationToken;

  StorageDriverSelectContextImpl(
      @Nullable StorageDriverTargetInfo target,
      CancellationToken<CancellationException> cancellationToken) {
    this.target = target;
    this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
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
