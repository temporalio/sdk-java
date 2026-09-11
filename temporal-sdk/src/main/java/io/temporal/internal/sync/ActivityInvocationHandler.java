package io.temporal.internal.sync;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@VisibleForTesting
public class ActivityInvocationHandler extends ActivityInvocationHandlerBase {
  private final ActivityOptions options;
  private final Map<String, ActivityOptions> activityMethodOptions;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      Class<?> activityInterface,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    return new ActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions, assertReadOnly);
  }

  private ActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions,
      Functions.Proc assertReadOnly) {
    super(activityInterface);
    this.options = options;
    this.activityMethodOptions = (methodOptions == null) ? new HashMap<>() : methodOptions;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  protected Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName) {
    ActivityOptions merged =
        ActivityOptions.newBuilder(options)
            .mergeActivityOptions(this.activityMethodOptions.get(activityName))
            .mergeMethodRetry(methodRetry)
            .build();

    if (ActivityInvocationInternal.isActive()) {
      ActivityInvocationOptions invocationOptions = ActivityInvocationInternal.consumeOptions();
      ActivityStub stub =
          newStub(ActivityStubImpl.resolveOptions(merged, invocationOptions), merged, activityName);
      return (a) -> {
        Promise<?> result =
            stub.executeAsync(
                activityName,
                method.getReturnType(),
                method.getGenericReturnType(),
                invocationOptions,
                a);
        ActivityInvocationInternal.setResult(result);
        return null;
      };
    }

    ActivityStub stub = newStub(merged, merged, activityName);
    return (a) ->
        stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
  }

  private ActivityStub newStub(
      ActivityOptions effectiveOptions, ActivityOptions stubOptions, String activityName) {
    if (effectiveOptions.getStartToCloseTimeout() == null
        && effectiveOptions.getScheduleToCloseTimeout() == null) {
      throw new IllegalArgumentException(
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for "
              + activityName
              + " activity. Please set at least one of the above through the ActivityStub or WorkflowImplementationOptions.");
    }
    return ActivityStubImpl.newInstance(stubOptions, activityExecutor, assertReadOnly);
  }

  @Override
  protected String proxyToString() {
    return "ActivityProxy{" + "options=" + options + '}';
  }
}
