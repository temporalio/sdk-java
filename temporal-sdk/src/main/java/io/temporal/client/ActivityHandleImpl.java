package io.temporal.client;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Package-private wrapper that adds typed result methods to an {@link UntypedActivityHandle},
 * implementing {@link ActivityHandle}{@code <R>}. Created via {@link
 * ActivityHandle#fromUntyped(UntypedActivityHandle, Class)} or {@link
 * ActivityHandle#fromUntyped(UntypedActivityHandle, Class, Type)}.
 */
final class ActivityHandleImpl<R> implements ActivityHandle<R> {

  private final UntypedActivityHandle delegate;
  private final Class<R> resultClass;
  private final @Nullable Type resultType;

  ActivityHandleImpl(
      UntypedActivityHandle delegate, Class<R> resultClass, @Nullable Type resultType) {
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
  public <T> T getResult(long timeout, TimeUnit unit, Class<T> clazz) throws TimeoutException {
    return delegate.getResult(timeout, unit, clazz, null);
  }

  @Override
  public <T> T getResult(long timeout, TimeUnit unit, Class<T> clazz, @Nullable Type type)
      throws TimeoutException {
    return delegate.getResult(timeout, unit, clazz, type);
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
  public <T> CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit, Class<T> clazz) {
    return delegate.getResultAsync(timeout, unit, clazz, null);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(
      long timeout, TimeUnit unit, Class<T> clazz, @Nullable Type type) {
    return delegate.getResultAsync(timeout, unit, clazz, type);
  }

  @Override
  public String getActivityId() {
    return delegate.getActivityId();
  }

  @Override
  public @Nullable String getActivityRunId() {
    return delegate.getActivityRunId();
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
  public <T> CompletableFuture<T> getResultAsync(Class<T> clazz) {
    return delegate.getResultAsync(clazz);
  }

  @Override
  public <T> CompletableFuture<T> getResultAsync(Class<T> clazz, @Nullable Type type) {
    return delegate.getResultAsync(clazz, type);
  }

  @Override
  public ActivityExecutionDescription describe() {
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
