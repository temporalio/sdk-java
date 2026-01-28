package io.temporal.internal.sync;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.LocalActivityOptions;
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
public class LocalActivityInvocationHandler extends ActivityInvocationHandlerBase {
  private final LocalActivityOptions options;
  private final Map<String, LocalActivityOptions> activityMethodOptions;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      Class<?> activityInterface,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> methodOptions,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    return new LocalActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions, assertReadOnly);
  }

  private LocalActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> methodOptions,
      Functions.Proc assertReadOnly) {
    super(activityInterface);
    this.options = options;
    this.activityMethodOptions = (methodOptions == null) ? new HashMap<>() : methodOptions;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
  }

  @VisibleForTesting
  @Override
  public Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName) {
    Function<Object[], Object> function;
    LocalActivityOptions mergedOptions =
        LocalActivityOptions.newBuilder(options)
            .mergeActivityOptions(activityMethodOptions.get(activityName))
            .setMethodRetry(methodRetry)
            .build();
    ActivityStub stub =
        LocalActivityStubImpl.newInstance(mergedOptions, activityExecutor, assertReadOnly);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }

  @Override
  protected String proxyToString() {
    return "LocalActivityProxy{" + "options='" + options + '}';
  }
}
