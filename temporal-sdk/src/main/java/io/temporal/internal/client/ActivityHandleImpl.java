package io.temporal.internal.client;

import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

/**
 * Implementation of {@link UntypedActivityHandle} that delegates lifecycle operations through the
 * interceptor chain.
 */
public final class ActivityHandleImpl implements UntypedActivityHandle {

  private final String activityId;
  private final @Nullable String activityRunId;
  private final ActivityClientCallsInterceptor clientCallsInterceptor;

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
  public <R> R getResult(Class<R> resultClass) throws ActivityFailedException {
    return getResult(resultClass, null);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, @Nullable Type resultType)
      throws ActivityFailedException {
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
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return getResult(resultClass, resultType);
          } catch (ActivityFailedException e) {
            throw new RuntimeException(e);
          }
        });
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
