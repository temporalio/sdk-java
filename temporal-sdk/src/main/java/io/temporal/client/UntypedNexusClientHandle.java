package io.temporal.client;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

public interface UntypedNexusClientHandle {
  /** Operation ID this handle was constructed for. Always non-null. */
  String getNexusOperationId();

  /**
   * Present if the handle was returned by `start` or set when calling `getHandle`. Null if
   * `getHandle` was called with a null run ID — in that case, use {@link #describe()} to learn the
   * current run ID.
   */
  @Nullable
  String getNexusOperationRunId();

  <R> R getResult(Class<R> resultClass);

  <R> R getResult(Class<R> resultClass, @Nullable Type resultType);

  /**
   * Block up to {@code timeout} for the operation to complete and return the typed result. Throws
   * {@link TimeoutException} if the operation has not completed within the deadline.
   */
  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass) throws TimeoutException;

  <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType)
      throws TimeoutException;

  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass);

  <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType);

  /**
   * Returns a future that completes with the typed result, or completes exceptionally with a {@link
   * TimeoutException} if {@code timeout} elapses before the operation finishes.
   */
  <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> resultClass);

  <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType);

  NexusClientOperationExecutionDescription describe();

  void cancel();

  void cancel(@Nullable String reason);

  void terminate();

  void terminate(@Nullable String reason);
}
