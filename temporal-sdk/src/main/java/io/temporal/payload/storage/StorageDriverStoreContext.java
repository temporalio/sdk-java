package io.temporal.payload.storage;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/** Context passed to {@link StorageDriver#store} and {@link StorageDriverSelector}. */
@Experimental
public final class StorageDriverStoreContext {
  private final @Nullable StorageDriverTargetInfo target;

  public StorageDriverStoreContext(@Nullable StorageDriverTargetInfo target) {
    this.target = target;
  }

  @Nullable
  public StorageDriverTargetInfo getTarget() {
    return target;
  }
}
