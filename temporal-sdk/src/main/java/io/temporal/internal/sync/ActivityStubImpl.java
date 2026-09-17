package io.temporal.internal.sync;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;

final class ActivityStubImpl extends ActivityStubBase {
  protected final ActivityOptions options;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  static ActivityStubBase newInstance(
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
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    return executeAsync(activityName, resultClass, resultType, null, args);
  }

  @Override
  <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Type[] argTypes, Object... args) {
    this.assertReadOnly.apply();
    return activityExecutor
        .executeActivity(
            new WorkflowOutboundCallsInterceptor.ActivityInput<>(
                activityName, resultClass, resultType, args, argTypes, options, Header.empty()))
        .getResult();
  }
}
