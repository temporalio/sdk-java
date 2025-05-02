package io.temporal.internal.sync;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
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
    Function<Object[], Object> function;
    ActivityOptions merged =
        ActivityOptions.newBuilder(options)
            .mergeActivityOptions(this.activityMethodOptions.get(activityName))
            .mergeMethodRetry(methodRetry)
            .build();
    if (merged.getStartToCloseTimeout() == null && merged.getScheduleToCloseTimeout() == null) {
      throw new IllegalArgumentException(
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for "
              + activityName
              + " activity. Please set at least one of the above through the ActivityStub or WorkflowImplementationOptions.");
    }
    ActivityStub stub = ActivityStubImpl.newInstance(merged, activityExecutor, assertReadOnly);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }

  @Override
  protected String proxyToString() {
    return "ActivityProxy{" + "options=" + options + '}';
  }
}
