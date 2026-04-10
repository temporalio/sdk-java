package io.temporal.internal.client;

import io.temporal.client.ActivityCancelOptions;
import io.temporal.client.ActivityDescribeOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.ActivityHandle;
import io.temporal.client.ActivityTerminateOptions;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ActivityHandle} that delegates lifecycle operations through the
 * interceptor chain.
 */
public final class ActivityHandleImpl implements ActivityHandle {

  private final String activityId;
  private final @Nullable String activityRunId;
  private final WorkflowClientCallsInterceptor clientCallsInterceptor;

  public ActivityHandleImpl(
      String activityId,
      @Nullable String activityRunId,
      WorkflowClientCallsInterceptor clientCallsInterceptor) {
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
  @SuppressWarnings("unchecked")
  public void getResult() throws ActivityFailedException {
    getResult((Class<Void>) Void.class);
  }

  @Override
  public <R> R getResult(Class<R> resultClass) throws ActivityFailedException {
    return clientCallsInterceptor
        .getActivityResult(
            new WorkflowClientCallsInterceptor.GetActivityResultInput<>(
                activityId, activityRunId, resultClass))
        .getResult();
  }

  @Override
  public ActivityExecutionDescription describe() {
    return describe(ActivityDescribeOptions.newBuilder().build());
  }

  @Override
  public ActivityExecutionDescription describe(ActivityDescribeOptions options) {
    return clientCallsInterceptor
        .describeActivity(
            new WorkflowClientCallsInterceptor.DescribeActivityInput(
                activityId, activityRunId, options))
        .getDescription();
  }

  @Override
  public void cancel() {
    cancel(ActivityCancelOptions.newBuilder().build());
  }

  @Override
  public void cancel(ActivityCancelOptions options) {
    clientCallsInterceptor.cancelActivity(
        new WorkflowClientCallsInterceptor.CancelActivityInput(activityId, activityRunId, options));
  }

  @Override
  public void terminate(@Nullable String reason) {
    terminate(reason, ActivityTerminateOptions.newBuilder().build());
  }

  @Override
  public void terminate(@Nullable String reason, ActivityTerminateOptions options) {
    clientCallsInterceptor.terminateActivity(
        new WorkflowClientCallsInterceptor.TerminateActivityInput(
            activityId, activityRunId, reason, options));
  }
}
