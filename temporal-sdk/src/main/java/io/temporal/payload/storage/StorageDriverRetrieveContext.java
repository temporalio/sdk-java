package io.temporal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.common.Experimental;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;

/**
 * Context passed to {@link StorageDriver#retrieve}.
 *
 * <p>Implemented by the SDK and passed to the driver. Driver authors do not implement this in
 * production code, only when constructing instances for their own tests.
 */
@Experimental
public interface StorageDriverRetrieveContext {
  /** Token cancelled when the SDK abandons this retrieve operation. */
  @Nonnull
  CancellationToken<CancellationException> getCancellationToken();
}
