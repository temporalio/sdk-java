package io.temporal.client;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Package-private wrapper that adds typed result methods to an {@link UntypedActivityHandle},
 * implementing {@link ActivityHandle}{@code <R>}. Created via {@link
 * ActivityHandle#fromUntyped(UntypedActivityHandle, Class)} or {@link
 * ActivityHandle#fromUntyped(UntypedActivityHandle, Class, Type)}.
 */
final class ActivityHandleWrapper<R> implements ActivityHandle<R> {

  private final UntypedActivityHandle delegate;
  private final Class<R> resultClass;
  private final @Nullable Type resultType;

  ActivityHandleWrapper(
      UntypedActivityHandle delegate, Class<R> resultClass, @Nullable Type resultType) {
    this.delegate = delegate;
    this.resultClass = resultClass;
    this.resultType = resultType;
  }

  @Override
  public R getResult() throws ActivityFailedException {
    return delegate.getResult(resultClass, resultType);
  }

  @Override
  public CompletableFuture<R> getResultAsync() {
    return delegate.getResultAsync(resultClass, resultType);
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
  public <T> T getResult(Class<T> clazz) throws ActivityFailedException {
    return delegate.getResult(clazz);
  }

  @Override
  public <T> T getResult(Class<T> clazz, @Nullable Type type) throws ActivityFailedException {
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
  public ActivityExecutionDescription describe(@Nullable byte[] longPollToken) {
    return delegate.describe(longPollToken);
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
