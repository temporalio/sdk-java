package io.temporal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.common.Experimental;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Context passed to {@link StorageDriver#store} and {@link StorageDriverSelector}.
 *
 * <p>Implemented by the SDK and passed to the driver. Driver authors do not implement this in
 * production code, only when constructing instances for their own tests.
 */
@Experimental
public interface StorageDriverStoreContext {
  /**
   * Identity of the workflow or activity the payload is being stored for, or {@code null} when it
   * is not available.
   */
  @Nullable
  StorageDriverTargetInfo getTarget();

  /** Token cancelled when the SDK abandons this store operation. */
  @Nonnull
  CancellationToken<CancellationException> getCancellationToken();
}
