package io.temporal.internal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class StorageDriverStoreContextImpl implements StorageDriverStoreContext {
  private final @Nullable StorageDriverTargetInfo target;
  private final String namespace;
  private final CancellationToken<CancellationException> cancellationToken;

  StorageDriverStoreContextImpl(
      @Nullable StorageDriverTargetInfo target,
      @Nonnull String namespace,
      CancellationToken<CancellationException> cancellationToken) {
    this.target = target;
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
  }

  @Nullable
  @Override
  public StorageDriverTargetInfo getTarget() {
    return target;
  }

  @Nonnull
  @Override
  public String getNamespace() {
    return namespace;
  }

  @Nonnull
  @Override
  public CancellationToken<CancellationException> getCancellationToken() {
    return cancellationToken;
  }
}
