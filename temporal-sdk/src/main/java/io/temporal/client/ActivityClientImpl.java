package io.temporal.client;

import com.uber.m3.tally.Scope;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.ActivityHandleImpl;
import io.temporal.internal.client.RootActivityClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
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
    this.invoker = initializeInvoker(genericClient, options);
    this.manualActivityCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            stubs, options.getNamespace(), options.getIdentity(), options.getDataConverter());
  }

  private static ActivityClientCallsInterceptor initializeInvoker(
      GenericWorkflowClientImpl genericClient, ActivityClientOptions options) {
    ActivityClientCallsInterceptor invoker = new RootActivityClientInvoker(genericClient, options);
    List<ActivityClientInterceptor> interceptors = options.getInterceptors();
    for (int i = interceptors.size() - 1; i >= 0; i--) {
      invoker = interceptors.get(i).activityClientCallsInterceptor(invoker);
    }
    return invoker;
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

  // ---- Typed-stub start (lambda overloads) ----

  @Override
  public <A1> ActivityHandle<Void> start(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, R> ActivityHandle<R> start(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException("Lambda-based start overloads are not yet implemented");
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

  // ---- Typed-stub execute ----

  @Override
  public <A1> void execute(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2> void execute(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3> void execute(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4> void execute(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5> void execute(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6> void execute(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7> void execute(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, R> R execute(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, R> R execute(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, R> R execute(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, R> R execute(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, R> R execute(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, R> R execute(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7, R> R execute(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException(
        "Lambda-based execute overloads are not yet implemented");
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

  // ---- Typed-stub executeAsync ----

  @Override
  public <A1> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
  }

  @Override
  public <A1, A2, A3, A4, A5, A6, A7, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7) {
    throw new UnsupportedOperationException(
        "Lambda-based executeAsync overloads are not yet implemented");
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
  public Stream<ActivityExecution> listExecutions(String query) {
    return listExecutions(query, ActivityListOptions.newBuilder().build());
  }

  @Override
  public Stream<ActivityExecution> listExecutions(String query, ActivityListOptions options) {
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
