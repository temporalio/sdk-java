package io.temporal.client;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * A typed handle to a standalone Nexus operation execution. Extends {@link
 * UntypedNexusOperationHandle} with typed result methods bound to a known result type.
 *
 * <p>Obtain an instance via {@link NexusServiceClient} or by wrapping an {@link
 * UntypedNexusOperationHandle} (returned by {@link NexusClient#getHandle(String)}) with {@link
 * #fromUntyped(UntypedNexusOperationHandle, Class)}.
 *
 * @param <R> the result type of the Nexus operation
 * @see UntypedNexusOperationHandle
 * @see NexusServiceClient
 * @see NexusClient
 */
@Experimental
public interface NexusOperationHandle<R> extends UntypedNexusOperationHandle {

  /**
   * Wraps an {@link UntypedNexusOperationHandle} with a known result type.
   *
   * @param handle the untyped handle to wrap
   * @param resultClass the class to deserialize the result into
   * @return a typed handle
   */
  static <R> NexusOperationHandle<R> fromUntyped(
      UntypedNexusOperationHandle handle, Class<R> resultClass) {
    return fromUntyped(handle, resultClass, null);
  }

  /**
   * Wraps an {@link UntypedNexusOperationHandle} with a known result type for generic types. Pass a
   * non-null {@code resultType} when the result is a generic type whose parameters cannot be
   * captured by {@link Class} alone (e.g. {@code List<String>}).
   *
   * @param handle the untyped handle to wrap
   * @param resultClass the class to deserialize the result into
   * @param resultType the generic type; may be {@code null}
   * @return a typed handle
   */
  static <R> NexusOperationHandle<R> fromUntyped(
      UntypedNexusOperationHandle handle, Class<R> resultClass, @Nullable Type resultType) {
    return NexusOperationHandleImpl.fromUntyped(handle, resultClass, resultType);
  }

  /**
   * Blocks until the Nexus operation completes and returns the typed result.
   *
   * @throws RuntimeException if the operation failed, timed out, or was cancelled
   */
  R getResult();

  /**
   * Blocks until the Nexus operation completes and returns the typed result, or throws if the
   * client-side timeout expires first.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   * @throws RuntimeException if the operation failed, timed out on the server, or was cancelled
   * @throws TimeoutException if {@code timeout} expires before the operation completes
   */
  R getResult(long timeout, TimeUnit unit) throws TimeoutException;

  /** Returns a future that completes when the Nexus operation completes with the typed result. */
  CompletableFuture<R> getResultAsync();

  /**
   * Returns a future that completes with the typed result, or completes exceptionally with a {@link
   * TimeoutException} if {@code timeout} elapses before the operation completes.
   *
   * @param timeout maximum time to wait
   * @param unit unit of {@code timeout}
   */
  CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit);
}
