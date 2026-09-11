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
  protected final ActivityOptions options;
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

  static ActivityOptions resolveOptions(
      ActivityOptions options, ActivityInvocationOptions invocationOptions) {
    ActivityOptions invocationActivityOptions = invocationOptions.getActivityOptions();
    if (invocationActivityOptions == null) {
      return options;
    }
    return ActivityOptions.newBuilder(invocationActivityOptions).validateAndBuildWithDefaults();
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    return executeAsyncInternal(activityName, resultClass, resultType, null, options, args);
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions invocationOptions,
      Object... args) {
    Objects.requireNonNull(invocationOptions, "invocationOptions");
    return executeAsyncInternal(
        activityName,
        resultClass,
        resultType,
        invocationOptions.getActivityId(),
        resolveOptions(options, invocationOptions),
        args);
  }

  private <R> Promise<R> executeAsyncInternal(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      String activityId,
      ActivityOptions options,
      Object... args) {
    this.assertReadOnly.apply();
    return activityExecutor
        .executeActivity(
            new WorkflowOutboundCallsInterceptor.ActivityInput<>(
                activityName, activityId, resultClass, resultType, args, options, Header.empty()))
        .getResult();
  }
}
