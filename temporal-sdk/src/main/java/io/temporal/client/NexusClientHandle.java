package io.temporal.client;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Typed handle for interacting with an existing standalone Nexus operation execution. Add a result
 * type binding to an {@link UntypedNexusClientHandle} (returned by {@link
 * NexusClient#getHandle(String)}) by calling one of the {@link #fromUntyped} factories.
 */
public interface NexusClientHandle<R> extends UntypedNexusClientHandle {

  /** Wrap an {@link UntypedNexusClientHandle} as a typed handle bound to {@code resultClass}. */
  static <R> NexusClientHandle<R> fromUntyped(
      UntypedNexusClientHandle handle, Class<R> resultClass) {
    return fromUntyped(handle, resultClass, null);
  }

  /**
   * Wrap an {@link UntypedNexusClientHandle} as a typed handle bound to {@code resultClass} and
   * {@code resultType}. Pass a non-null {@code resultType} when the result is a generic type whose
   * parameters cannot be captured by {@link Class} alone (e.g. {@code List<String>}).
   */
  static <R> NexusClientHandle<R> fromUntyped(
      UntypedNexusClientHandle handle, Class<R> resultClass, @Nullable Type resultType) {
    return NexusClientHandleImpl.fromUntyped(handle, resultClass, resultType);
  }

  /** Block until the operation completes and return the typed result. */
  R getResult();

  /** Block up to {@code timeout} for the operation to complete and return the typed result. */
  R getResult(long timeout, java.util.concurrent.TimeUnit unit)
      throws java.util.concurrent.TimeoutException;

  /** Returns a future that completes with the typed result when the operation finishes. */
  CompletableFuture<R> getResultAsync();

  /**
   * Returns a future that completes with the typed result, or completes exceptionally with a {@link
   * java.util.concurrent.TimeoutException} if {@code timeout} elapses first.
   */
  CompletableFuture<R> getResultAsync(long timeout, java.util.concurrent.TimeUnit unit);
}
