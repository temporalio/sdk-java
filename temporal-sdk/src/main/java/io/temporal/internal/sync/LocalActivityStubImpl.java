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
import javax.annotation.Nullable;

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
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    return executeAsyncInternal(activityName, resultClass, resultType, null, args);
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      ActivityInvocationOptions invocationOptions,
      Object... args) {
    Objects.requireNonNull(invocationOptions, "invocationOptions");
    if (invocationOptions.getActivityOptions() != null) {
      throw new IllegalArgumentException("ActivityOptions are not supported for Local Activities");
    }
    return executeAsyncInternal(
        activityName, resultClass, resultType, invocationOptions.getActivityId(), args);
  }

  private <R> Promise<R> executeAsyncInternal(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      @Nullable String activityId,
      Object... args) {
    this.assertReadOnly.apply();
    return activityExecutor
        .executeLocalActivity(
            new WorkflowOutboundCallsInterceptor.LocalActivityInput<>(
                activityName, activityId, resultClass, resultType, args, options, Header.empty()))
        .getResult();
  }
}
