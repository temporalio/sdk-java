package io.temporal.internal.client;

import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityExecutionOptions;
import io.temporal.client.ActivityOptionsUpdate;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.PauseActivityOptions;
import io.temporal.client.UnpauseActivityOptions;
import io.temporal.client.UntypedActivityHandle;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  public <R> R getResult(Class<R> resultClass) {
    return getResult(resultClass, null);
  }

  @Override
  public <R> R getResult(Class<R> resultClass, @Nullable Type resultType) {
    try {
      return clientCallsInterceptor
          .getActivityResult(
              new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                  activityId, activityRunId, resultClass, resultType))
          .getResult();
    } catch (TimeoutException e) {
      // unreachable: no-timeout input uses Long.MAX_VALUE deadline
      throw new RuntimeException(e);
    }
  }

  @Override
  public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass)
      throws TimeoutException {
    return getResult(timeout, unit, resultClass, null);
  }

  @Override
  public <R> R getResult(
      long timeout, TimeUnit unit, Class<R> resultClass, @Nullable Type resultType)
      throws TimeoutException {
    return clientCallsInterceptor
        .getActivityResult(
            new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass, resultType, timeout, unit))
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
    return clientCallsInterceptor
        .getActivityResultAsync(
            new ActivityClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass, resultType, timeout, unit))
        .thenApply(ActivityClientCallsInterceptor.GetActivityResultOutput::getResult);
  }

  @Override
  public ActivityExecutionDescription describe() {
    return describe(DescribeActivityOptions.getDefaultInstance());
  }

  @Override
  public ActivityExecutionDescription describe(DescribeActivityOptions options) {
    return clientCallsInterceptor
        .describeActivity(
            new ActivityClientCallsInterceptor.DescribeActivityInput(
                activityId, activityRunId, options))
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

  @Override
  public void pause() {
    pause(PauseActivityOptions.getDefaultInstance());
  }

  @Override
  public void pause(PauseActivityOptions options) {
    clientCallsInterceptor.pauseActivity(
        new ActivityClientCallsInterceptor.PauseActivityInput(activityId, activityRunId, options));
  }

  @Override
  public void unpause() {
    unpause(UnpauseActivityOptions.getDefaultInstance());
  }

  @Override
  public void unpause(UnpauseActivityOptions options) {
    clientCallsInterceptor.unpauseActivity(
        new ActivityClientCallsInterceptor.UnpauseActivityInput(
            activityId, activityRunId, options));
  }

  @Override
  public ActivityExecutionOptions updateOptions(ActivityOptionsUpdate<?>... updates) {
    List<ActivityOptionsUpdate<?>> list = Arrays.asList(updates);

    // An update naming nothing would send an empty mask and silently change nothing. Fail here
    // rather than making a round trip that looks like it worked. Use restoreOriginalOptions() to
    // revert options instead.
    if (list.isEmpty()) {
      throw new IllegalArgumentException("updateOptions requires at least one option update");
    }

    ActivityClientCallsInterceptor.UpdateActivityOptionsOutput output =
        clientCallsInterceptor.updateActivityOptions(
            new ActivityClientCallsInterceptor.UpdateActivityOptionsInput(
                activityId, activityRunId, list, false));

    return output.getOptions();
  }

  @Override
  public ActivityExecutionOptions restoreOriginalOptions() {
    ActivityClientCallsInterceptor.UpdateActivityOptionsOutput output =
        clientCallsInterceptor.updateActivityOptions(
            new ActivityClientCallsInterceptor.UpdateActivityOptionsInput(
                activityId, activityRunId, Collections.emptyList(), true));
    return output.getOptions();
  }
}
