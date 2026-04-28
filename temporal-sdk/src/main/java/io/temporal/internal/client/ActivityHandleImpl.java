package io.temporal.internal.client;

import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Implementation of {@link UntypedActivityHandle} that delegates lifecycle operations through the
 * interceptor chain.
 */
public final class ActivityHandleImpl implements UntypedActivityHandle {

  private final String activityId;
  private final @Nullable String activityRunId;
  private final ActivityClientCallsInterceptor clientCallsInterceptor;
  private final AtomicReference<CompletableFuture<?>> noTimeoutResult = new AtomicReference<>();

  public ActivityHandleImpl(
      String activityId,
      @Nullable String activityRunId,
      ActivityClientCallsInterceptor clientCallsInterceptor) {
    this.activityId = activityId;
    this.activityRunId = activityRunId;
    this.clientCallsInterceptor = clientCallsInterceptor;
  }

  @Override
  public String getActivityId() {
    return activityId;
  }

  @Override
  public @Nullable String getActivityRunId() {
    return activityRunId;
  }

  @Override
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, null);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, @Nullable Type resultType) {
    return clientCallsInterceptor
        .getActivityResult(
            new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass, resultType))
        .getResult();
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
    return getResultAsync(resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, @Nullable Type resultType) {
    return getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS, resultClass, resultType);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass) {
    return getResultAsync(timeout, unit, resultClass, null);
  }

  @Override
  public <R> CompletableFuture<R> getResultAsync(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType) {
    boolean noTimeout = timeout == Long.MAX_VALUE && unit == TimeUnit.MILLISECONDS;

    CompletableFuture<?> cached = noTimeoutResult.get();
    if (cached != null && (noTimeout || cached.isDone())) {
      @SuppressWarnings("unchecked")
      CompletableFuture<R> typed = (CompletableFuture<R>) cached;
      return typed;
    }

    CompletableFuture<R> newFuture =
        clientCallsInterceptor
            .getActivityResultAsync(
                new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                    activityId, activityRunId, resultClass, resultType, timeout, unit))
            .thenApply(ActivityClientCallsInterceptor.GetActivityResultOutput::getResult);

    // When a timed call succeeds the activity is done; populate cache so future calls skip polling.
    newFuture.whenComplete(
        (r, ex) -> {
          if (ex == null) {
            noTimeoutResult.compareAndSet(null, newFuture);
          }
        });

    if (noTimeout && noTimeoutResult.compareAndSet(null, newFuture)) {
      return newFuture;
    }

    // Another thread raced us on the first no-timeout call; return the winner
    cached = noTimeoutResult.get();
    if (cached != null && (noTimeout || cached.isDone())) {
      @SuppressWarnings("unchecked")
      CompletableFuture<R> typed = (CompletableFuture<R>) cached;
      return typed;
    }

    return newFuture;
  }

  @Override
  public ActivityExecutionDescription describe() {
    return clientCallsInterceptor
        .describeActivity(
            new ActivityClientCallsInterceptor.DescribeActivityInput(activityId, activityRunId))
        .getDescription();
  }

  @Override
  public void cancel() {
    cancel(null);
  }

  @Override
  public void cancel(@Nullable String reason) {
    clientCallsInterceptor.cancelActivity(
        new ActivityClientCallsInterceptor.CancelActivityInput(activityId, activityRunId, reason));
  }

  @Override
  public void terminate() {
    terminate(null);
  }

  @Override
  public void terminate(@Nullable String reason) {
    clientCallsInterceptor.terminateActivity(
        new ActivityClientCallsInterceptor.TerminateActivityInput(
            activityId, activityRunId, reason));
  }
}
