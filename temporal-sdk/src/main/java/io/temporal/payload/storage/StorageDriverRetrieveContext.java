package io.temporal.payload.storage;

import io.temporal.common.CancellationToken;
import io.temporal.common.Experimental;
import java.util.concurrent.CancellationException;
import javax.annotation.Nonnull;

/**
 * Context passed to {@link StorageDriver#retrieve}.
 *
 * <p>The SDK supplies the instance a driver receives. Members added here in later releases will
 * carry a default, so an existing driver-side implementation keeps compiling and behaves as though
 * the new member were absent.
 */
@Experimental
public interface StorageDriverRetrieveContext {
  /**
   * Token cancelled when the SDK abandons this retrieve operation. Defaults to a token that is
   * never cancelled.
   */
  @Nonnull
  default CancellationToken<CancellationException> getCancellationToken() {
    return CancellationToken.none();
  }
}
