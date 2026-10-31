package io.temporal.internal.sync;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;
import java.util.Objects;

final class ActivityStubImpl extends ActivityStubBase {
  private final ActivityOptions options;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  static ActivityStub newInstance(
      ActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    ActivityOptions validatedOptions =
        ActivityOptions.newBuilder(options).validateAndBuildWithDefaults();
    return new ActivityStubImpl(validatedOptions, activityExecutor, assertReadOnly);
  }

  ActivityStubImpl(
      ActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    this.options = options;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public <R> R execute(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions invocationOptions,
      Object... args) {
    Objects.requireNonNull(invocationOptions, "invocationOptions");
    return getResult(
        scheduleActivity(
            activityName, resultClass, resultType, invocationOptions.getActivityId(), args),
        resultClass);
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions invocationOptions,
      Object... args) {
    Objects.requireNonNull(invocationOptions, "invocationOptions");
    return scheduleActivity(
        activityName, resultClass, resultType, invocationOptions.getActivityId(), args);
  }

  private <R> Promise<R> scheduleActivity(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      String activityId,
      Object... args) {
    this.assertReadOnly.apply();
    return activityExecutor
        .executeActivity(
            new WorkflowOutboundCallsInterceptor.ActivityInput<>(
                activityName,
                activityId,
                resultClass,
                resultType,
                args,
                this.options,
                Header.empty()))
        .getResult();
  }
}
