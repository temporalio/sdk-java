package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A typed handle to a standalone activity execution. Extends {@link UntypedActivityHandle} with
 * typed result methods.
 *
 * <p>Obtain an instance via {@link ActivityClient#start(String, Class, StartActivityOptions,
 * Object...)} or {@link ActivityClient#getHandle(String, String, Class)}.
 *
 * @param <R> the result type of the activity
 * @see UntypedActivityHandle
 * @see ActivityClient
 */
@Experimental
public interface ActivityHandle<R> extends UntypedActivityHandle {

  /**
   * Blocks until the standalone activity completes and returns the typed result.
   *
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  R getResult() throws ActivityFailedException;

  /**
   * Returns a future that completes when the activity completes and resolves to the typed result.
   */
  CompletableFuture<R> getResultAsync();

  /**
   * Returns a future that completes when the activity completes, or fails with {@link
   * java.util.concurrent.TimeoutException} if the activity does not complete within the specified
   * timeout.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   */
  CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit);

  /**
   * Wraps an {@link UntypedActivityHandle} with a known result type.
   *
   * @param handle the untyped handle to wrap
   * @param resultClass the class to deserialize the result into
   * @return a typed handle
   */
  static <R> ActivityHandle<R> fromUntyped(UntypedActivityHandle handle, Class<R> resultClass) {
    return fromUntyped(handle, resultClass, null);
  }

  /**
   * Wraps an {@link UntypedActivityHandle} with a known result type for generic types.
   *
   * @param handle the untyped handle to wrap
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type; may be {@code null}
   * @return a typed handle
   */
  static <R> ActivityHandle<R> fromUntyped(
      UntypedActivityHandle handle, Class<R> resultClass, @Nullable Type resultType) {
    return new ActivityHandleImpl<>(handle, resultClass, resultType);
  }
}
