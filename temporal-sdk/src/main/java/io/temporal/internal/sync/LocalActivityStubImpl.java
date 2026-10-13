package io.temporal.internal.sync;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;
import java.util.Objects;

class LocalActivityStubImpl extends ActivityStubBase {
  protected final LocalActivityOptions options;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  static ActivityStub newInstance(
      LocalActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    LocalActivityOptions validatedOptions =
        LocalActivityOptions.newBuilder(options).validateAndBuildWithDefaults();
    return new LocalActivityStubImpl(validatedOptions, activityExecutor, assertReadOnly);
  }

  private LocalActivityStubImpl(
      LocalActivityOptions options,
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
        scheduleActivity(activityName, resultClass, resultType, invocationOptions, args),
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
    return scheduleActivity(activityName, resultClass, resultType, invocationOptions, args);
  }

  private <R> Promise<R> scheduleActivity(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions invocationOptions,
      Object... args) {
    this.assertReadOnly.apply();
    if (invocationOptions.getActivityOptionsOverride() != null) {
      throw new IllegalArgumentException(
          "Activity options overrides are not supported for Local Activities");
    }
    return activityExecutor
        .executeLocalActivity(
            new WorkflowOutboundCallsInterceptor.LocalActivityInput<>(
                activityName,
                invocationOptions.getActivityId(),
                resultClass,
                resultType,
                args,
                options,
                Header.empty()))
        .getResult();
  }
}
