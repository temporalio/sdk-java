package io.temporal.client;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Package-private wrapper that adds typed result methods to an {@link UntypedNexusOperationHandle},
 * implementing {@link NexusOperationHandle}{@code <R>}. Created via {@link
 * NexusOperationHandle#fromUntyped(UntypedNexusOperationHandle, Class)} or {@link
 * NexusOperationHandle#fromUntyped(UntypedNexusOperationHandle, Class, Type)}.
 */
final class NexusOperationHandleImpl<R> implements NexusOperationHandle<R> {

  private final UntypedNexusOperationHandle delegate;
  private final Class<R> resultClass;
  private final @Nullable Type resultType;

  NexusOperationHandleImpl(
      UntypedNexusOperationHandle delegate, Class<R> resultClass, @Nullable Type resultType) {
    this.delegate = delegate;
    this.resultClass = resultClass;
    this.resultType = resultType;
  }

  @Override
  public R getResult() {
    return delegate.getResult(resultClass, resultType);
  }

  @Override
  public R getResult(long timeout, TimeUnit unit) throws TimeoutException {
    return delegate.getResult(timeout, unit, resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync() {
    return delegate.getResultAsync(resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit) {
    return delegate.getResultAsync(timeout, unit, resultClass, resultType);
  }

  @Override
  public String getNexusOperationId() {
    return delegate.getNexusOperationId();
  }

  @Override
  public @Nullable String getNexusOperationRunId() {
    return delegate.getNexusOperationRunId();
  }

  @Override
  public <T> T getResult(Class<T> clazz) {
    return delegate.getResult(clazz);
  }

  @Override
  public <T> T getResult(Class<T> clazz, @Nullable Type type) {
    return delegate.getResult(clazz, type);
  }

  @Override
  public <T> T getResult(long timeout, TimeUnit unit, Class<T> clazz) throws TimeoutException {
    return delegate.getResult(timeout, unit, clazz, null);
  }

  @Override
  public <T> T getResult(long timeout, TimeUnit unit, Class<T> clazz, @Nullable Type type)
      throws TimeoutException {
    return delegate.getResult(timeout, unit, clazz, type);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(Class<T> clazz) {
    return delegate.getResultAsync(clazz);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(Class<T> clazz, @Nullable Type type) {
    return delegate.getResultAsync(clazz, type);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit, Class<T> clazz) {
    return delegate.getResultAsync(timeout, unit, clazz, null);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(
      long timeout, TimeUnit unit, Class<T> clazz, @Nullable Type type) {
    return delegate.getResultAsync(timeout, unit, clazz, type);
  }

  @Override
  public NexusOperationExecutionDescription describe() {
    return delegate.describe();
  }

  @Override
  public void cancel() {
    delegate.cancel();
  }

  @Override
  public void cancel(@Nullable String reason) {
    delegate.cancel(reason);
  }

  @Override
  public void terminate() {
    delegate.terminate();
  }

  @Override
  public void terminate(@Nullable String reason) {
    delegate.terminate(reason);
  }
}
