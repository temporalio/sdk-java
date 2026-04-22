package io.temporal.client;

import com.google.common.base.Defaults;
import com.uber.m3.tally.Scope;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.common.metadata.POJOActivityInterfaceMetadata;
import io.temporal.internal.client.ActivityHandleImpl;
import io.temporal.internal.client.RootActivityClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
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

  // ---- Type-sniffing helpers ----

  /**
   * Creates a dynamic proxy of {@code activityInterface} that records the invoked {@link Method} in
   * {@code captured[0]} when any method is called on it.
   */
  @SuppressWarnings("unchecked")
  private static <I> I createTypeProbe(Class<I> activityInterface, Method[] captured) {
    return (I)
        Proxy.newProxyInstance(
            activityInterface.getClassLoader(),
            new Class<?>[] {activityInterface},
            (proxy, method, args) -> {
              captured[0] = method;
              return Defaults.defaultValue(method.getReturnType());
            });
  }

  /**
   * Derives the Temporal activity type name from the {@link Method} that was captured by {@link
   * #createTypeProbe}.
   */
  private static String extractActivityType(Class<?> activityInterface, Method method) {
    POJOActivityInterfaceMetadata metadata =
        POJOActivityInterfaceMetadata.newInstance(activityInterface);
    return metadata.getMethodMetadata(method).getActivityTypeName();
  }

  // ---- Interface-based start (Proc variants) ----

  @Override
  public <I> ActivityHandle<Void> start(
      Class<I> activityInterface, Functions.Proc1<I> activity, StartActivityOptions options)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(extractActivityType(activityInterface, captured[0]), options, new Object[0]);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(extractActivityType(activityInterface, captured[0]), options, arg1);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(extractActivityType(activityInterface, captured[0]), options, arg1, arg2);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  @Override
  public <I, A1, A2, A3> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(extractActivityType(activityInterface, captured[0]), options, arg1, arg2, arg3);
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
      A4 arg4)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(extractActivityType(activityInterface, captured[0]), options, arg1, arg2, arg3, arg4);
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
      A5 arg5)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4, arg5);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(
            extractActivityType(activityInterface, captured[0]),
            options,
            arg1,
            arg2,
            arg3,
            arg4,
            arg5);
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
      A6 arg6)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4, arg5, arg6);
    } catch (Throwable ignored) {
    }
    UntypedActivityHandle untyped =
        start(
            extractActivityType(activityInterface, captured[0]),
            options,
            arg1,
            arg2,
            arg3,
            arg4,
            arg5,
            arg6);
    return ActivityHandle.fromUntyped(untyped, Void.class, null);
  }

  // ---- Interface-based start (Func variants) ----

  @Override
  public <I, R> ActivityHandle<R> start(
      Class<I> activityInterface, Functions.Func1<I, R> activity, StartActivityOptions options)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, new Object[0]);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
    UntypedActivityHandle untyped = start(activityType, options, arg1);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  @Override
  public <I, A1, A2, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
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
      A3 arg3)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
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
      A4 arg4)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
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
      A5 arg5)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4, arg5);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
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
      A6 arg6)
      throws ActivityAlreadyStartedException {
    Method[] captured = {null};
    I probe = createTypeProbe(activityInterface, captured);
    try {
      activity.apply(probe, arg1, arg2, arg3, arg4, arg5, arg6);
    } catch (Throwable ignored) {
    }
    String activityType = extractActivityType(activityInterface, captured[0]);
    @SuppressWarnings("unchecked")
    Class<R> resultClass = (Class<R>) captured[0].getReturnType();
    Type resultType = captured[0].getGenericReturnType();
    UntypedActivityHandle untyped =
        start(activityType, options, arg1, arg2, arg3, arg4, arg5, arg6);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  // ---- String-based start ----

  @Override
  public UntypedActivityHandle start(
      String activityType, StartActivityOptions options, @Nullable Object... args)
      throws ActivityAlreadyStartedException {
    ActivityClientCallsInterceptor.StartActivityOutput output =
        invoker.startActivity(
            new ActivityClientCallsInterceptor.StartActivityInput(
                activityType,
                Arrays.asList(args != null ? args : new Object[0]),
                options,
                Header.empty()));
    return new ActivityHandleImpl(output.getActivityId(), output.getActivityRunId(), invoker);
  }

  @Override
  public <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityAlreadyStartedException {
    return start(activityType, resultClass, null, options, args);
  }

  @Override
  public <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityAlreadyStartedException {
    UntypedActivityHandle untyped = start(activityType, options, args);
    return ActivityHandle.fromUntyped(untyped, resultClass, resultType);
  }

  // ---- String-based execute ----

  @Override
  @SuppressWarnings("unchecked")
  public void execute(String activityType, StartActivityOptions options, @Nullable Object... args)
      throws ActivityFailedException {
    start(activityType, options, args).getResult(Void.class);
  }

  @Override
  public <R> R execute(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityFailedException {
    return start(activityType, resultClass, options, args).getResult();
  }

  @Override
  public <R> R execute(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityFailedException {
    return start(activityType, resultClass, resultType, options, args).getResult();
  }

  // ---- String-based executeAsync ----

  @Override
  public CompletableFuture<Void> executeAsync(
      String activityType, StartActivityOptions options, @Nullable Object... args) {
    return start(activityType, options, args).getResultAsync(Void.class);
  }

  @Override
  public <R> CompletableFuture<R> executeAsync(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args) {
    return start(activityType, resultClass, options, args).getResultAsync();
  }

  @Override
  public <R> CompletableFuture<R> executeAsync(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args) {
    return start(activityType, resultClass, resultType, options, args).getResultAsync();
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

  // ---- List / count ----

  @Override
  public Stream<ActivityExecutionMetadata> listExecutions(String query) {
    return listExecutions(query, ActivityListOptions.newBuilder().build());
  }

  @Override
  public Stream<ActivityExecutionMetadata> listExecutions(
      String query, ActivityListOptions options) {
    return invoker
        .listActivities(new ActivityClientCallsInterceptor.ListActivitiesInput(query, options))
        .getStream();
  }

  @Override
  public ActivityListPage listExecutionsPaginated(
      String query, @Nullable byte[] nextPageToken, ActivityListPaginatedOptions options) {
    return invoker
        .listActivitiesPaginated(
            new ActivityClientCallsInterceptor.ListActivitiesPaginatedInput(
                query, nextPageToken, options))
        .getPage();
  }

  @Override
  public ActivityExecutionCount countExecutions(String query) {
    return countExecutions(query, ActivityCountOptions.newBuilder().build());
  }

  @Override
  public ActivityExecutionCount countExecutions(String query, ActivityCountOptions options) {
    return invoker
        .countActivities(new ActivityClientCallsInterceptor.CountActivitiesInput(query, options))
        .getCount();
  }

  // ---- Completion client ----

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    return new ActivityCompletionClientImpl(
        manualActivityCompletionClientFactory, () -> {}, metricsScope, null);
  }
}
