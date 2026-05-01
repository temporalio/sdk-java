package io.temporal.client;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.ActivityHandleImpl;
import io.temporal.internal.client.RootActivityClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.util.MethodExtractor;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Implementation of {@link ActivityClient} that delegates calls through the activity interceptor
 * chain and ultimately to the Temporal service.
 */
class ActivityClientImpl implements ActivityClient {

  private final WorkflowServiceStubs stubs;
  private final ActivityClientOptions options;
  private final ActivityClientCallsInterceptor invoker;
  private final ManualActivityCompletionClientFactory manualActivityCompletionClientFactory;
  private final Scope metricsScope;

  ActivityClientImpl(WorkflowServiceStubs stubs, ActivityClientOptions options) {
    this.stubs = stubs;
    this.options = options;
    this.metricsScope =
        stubs.getOptions().getMetricsScope().tagged(MetricsTag.defaultTags(options.getNamespace()));
    GenericWorkflowClientImpl genericClient = new GenericWorkflowClientImpl(stubs, metricsScope);
    this.invoker = initializeClientInvoker(genericClient, options);
    this.manualActivityCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            stubs, options.getNamespace(), options.getIdentity(), options.getDataConverter());
  }

  private static ActivityClientCallsInterceptor initializeClientInvoker(
      GenericWorkflowClientImpl genericClient, ActivityClientOptions options) {
    ActivityClientCallsInterceptor invoker = new RootActivityClientInvoker(genericClient, options);
    for (ActivityClientInterceptor interceptor : options.getInterceptors()) {
      invoker = interceptor.activityClientCallsInterceptor(invoker);
    }
    return invoker;
  }

  // ---- Interface-based start (Proc variants) ----

  @Override
  public <I> ActivityHandle<Void> start(
      Class<I> activityInterface, Functions.Proc1<I> activity, StartActivityOptions options) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, new Object[0]);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activity,
      StartActivityOptions options,
      A1 arg1) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, arg1);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2, A3> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2, A3, A4> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc5<I, A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3, arg4);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2, A3, A4, A5> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc6<I, A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3, arg4, arg5);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, A6> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc7<I, A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    String activityType =
        MethodExtractor.activityTypeName(
            activityInterface, MethodExtractor.extract(activityInterface, activity));
    UntypedActivityHandle untyped =
        start(activityType, options, arg1, arg2, arg3, arg4, arg5, arg6);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  // ---- Interface-based start (Func variants) ----

  @Override
  public <I, R> ActivityHandle<R> start(
      Class<I> activityInterface, Functions.Func1<I, R> activity, StartActivityOptions options) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, new Object[0]);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activity,
      StartActivityOptions options,
      A1 arg1) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, A3, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func4<I, A1, A2, A3, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, A3, A4, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func5<I, A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3, arg4);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func6<I, A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1, arg2, arg3, arg4, arg5);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, A3, A4, A5, A6, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    Method method = MethodExtractor.extract(activityInterface, activity);
    String activityType = MethodExtractor.activityTypeName(activityInterface, method);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) method.getReturnType();
    Type resultType = method.getGenericReturnType();
    UntypedActivityHandle untyped =
        start(activityType, options, arg1, arg2, arg3, arg4, arg5, arg6);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  // ---- String-based start ----

  @Override
  public UntypedActivityHandle start(
      String activityType, StartActivityOptions options, @Nullable Object... args) {
    ActivityClientCallsInterceptor.StartActivityOutput output =
        invoker.startActivity(
            new ActivityClientCallsInterceptor.StartActivityInput(
                activityType,
                Arrays.asList(args != null ? args : new Object[0]),
                options,
                propagatedHeader()));
    return new ActivityHandleImpl(output.getActivityId(), output.getActivityRunId(), invoker);
  }

  @Override
  public <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args) {
    return start(activityType, resultClass, null, options, args);
  }

  @Override
  public <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args) {
    UntypedActivityHandle untyped = start(activityType, options, args);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  // ---- Handle lookup ----

  @Override
  public UntypedActivityHandle getHandle(String activityId, @Nullable String activityRunId) {
    return new ActivityHandleImpl(activityId, activityRunId, invoker);
  }

  @Override
  public <R> ActivityHandle<R> getHandle(
      String activityId, @Nullable String activityRunId, Class<R> resultClass) {
    return getHandle(activityId, activityRunId, resultClass, null);
  }

  @Override
  public <R> ActivityHandle<R> getHandle(
      String activityId,
      @Nullable String activityRunId,
      Class<R> resultClass,
      @Nullable Type resultType) {
    UntypedActivityHandle untyped = getHandle(activityId, activityRunId);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  private Header propagatedHeader() {
    List<ContextPropagator> propagators = options.getContextPropagators();
    if (propagators.isEmpty()) {
      return Header.empty();
    }
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : propagators) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new Header(result);
  }

  // ---- List / count ----

  @Override
  public Stream<ActivityExecutionMetadata> listExecutions(String query) {
    return invoker
        .listActivities(new ActivityClientCallsInterceptor.ListActivitiesInput(query))
        .getStream();
  }

  @Override
  public ActivityExecutionCount countExecutions(String query) {
    return invoker
        .countActivities(new ActivityClientCallsInterceptor.CountActivitiesInput(query))
        .getCount();
  }

  // ---- Completion client ----

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    return new ActivityCompletionClientImpl(
        manualActivityCompletionClientFactory, () -> {}, metricsScope, null);
  }
}
