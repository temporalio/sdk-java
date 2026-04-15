package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Client for managing standalone activity executions. Obtain an instance via {@link
 * #newInstance(WorkflowServiceStubs)} or {@link #newInstance(WorkflowServiceStubs,
 * ActivityClientOptions)}.
 *
 * <p>Standalone activities run independently of any workflow. They are scheduled, monitored, and
 * managed directly through this client rather than from within a workflow execution.
 */
@Experimental
public interface ActivityClient {

  /** Creates a new {@code ActivityClient} with default options. */
  static ActivityClient newInstance(WorkflowServiceStubs stubs) {
    return newInstance(stubs, ActivityClientOptions.getDefaultInstance());
  }

  /** Creates a new {@code ActivityClient} with the given options. */
  static ActivityClient newInstance(WorkflowServiceStubs stubs, ActivityClientOptions options) {
    return new ActivityClientImpl(stubs, options);
  }

  // ---- String-based start ----

  /**
   * Starts a standalone activity by type name and returns an untyped handle to it.
   *
   * @param activityType the registered activity type name
   * @param options scheduling options (task queue, timeouts, etc.)
   * @param args activity arguments
   * @return an untyped handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  UntypedActivityHandle start(
      String activityType, StartActivityOptions options, @Nullable Object... args)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity by type name and returns a typed handle.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result type
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return a typed handle whose {@link ActivityHandle#getResult()} returns {@code R}
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity by type name and returns a typed handle for generic result types.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result class
   * @param resultType generic type for deserialisation (e.g. from {@code TypeToken.getType()})
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return a typed handle
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <R> ActivityHandle<R> start(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityAlreadyStartedException;

  // ---- Typed-stub start (lambda overloads, Proc/Func 1-7) ----

  <A1> ActivityHandle<Void> start(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options);

  <A1, A2> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7> ActivityHandle<Void> start(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  <A1, R> ActivityHandle<R> start(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options);

  <A1, A2, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7, R> ActivityHandle<R> start(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  // ---- String-based execute (synchronous) ----

  /**
   * Starts a standalone activity and blocks until it completes, discarding the result.
   *
   * @param activityType the registered activity type name
   * @param options scheduling options
   * @param args activity arguments
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  void execute(String activityType, StartActivityOptions options, @Nullable Object... args)
      throws ActivityFailedException;

  /**
   * Starts a standalone activity and blocks until it completes, returning the typed result.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result type
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  <R> R execute(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityFailedException;

  /**
   * Starts a standalone activity and blocks until it completes, for generic result types.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result class
   * @param resultType generic type for deserialisation
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  <R> R execute(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args)
      throws ActivityFailedException;

  // ---- Typed-stub execute (lambda overloads, Proc/Func 1-7) ----

  <A1> void execute(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options);

  <A1, A2> void execute(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3> void execute(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4> void execute(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5> void execute(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6> void execute(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7> void execute(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  <A1, R> R execute(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options);

  <A1, A2, R> R execute(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3, R> R execute(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4, R> R execute(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5, R> R execute(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6, R> R execute(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7, R> R execute(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  // ---- String-based executeAsync ----

  /**
   * Starts a standalone activity and returns a future that completes when the activity does,
   * discarding the result.
   *
   * @param activityType the registered activity type name
   * @param options scheduling options
   * @param args activity arguments
   * @return a future that completes when the activity completes
   */
  CompletableFuture<Void> executeAsync(
      String activityType, StartActivityOptions options, @Nullable Object... args);

  /**
   * Starts a standalone activity and returns a future that completes with the typed result.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result type
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return a future that resolves to the activity result
   */
  <R> CompletableFuture<R> executeAsync(
      String activityType,
      Class<R> resultClass,
      StartActivityOptions options,
      @Nullable Object... args);

  /**
   * Starts a standalone activity and returns a future, for generic result types.
   *
   * @param activityType the registered activity type name
   * @param resultClass expected result class
   * @param resultType generic type for deserialisation
   * @param options scheduling options
   * @param args activity arguments
   * @param <R> result type
   * @return a future that resolves to the activity result
   */
  <R> CompletableFuture<R> executeAsync(
      String activityType,
      Class<R> resultClass,
      Type resultType,
      StartActivityOptions options,
      @Nullable Object... args);

  // ---- Typed-stub executeAsync (lambda overloads, Proc/Func 1-7) ----

  <A1> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface, Functions.Proc1<A1> activity, StartActivityOptions options);

  <A1, A2> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc2<A1, A2> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc3<A1, A2, A3> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc4<A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc5<A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc6<A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7> CompletableFuture<Void> executeAsync(
      Class<A1> activityInterface,
      Functions.Proc7<A1, A2, A3, A4, A5, A6, A7> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  <A1, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface, Functions.Func1<A1, R> activity, StartActivityOptions options);

  <A1, A2, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func2<A1, A2, R> activity,
      StartActivityOptions options,
      A2 arg2);

  <A1, A2, A3, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func3<A1, A2, A3, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3);

  <A1, A2, A3, A4, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func4<A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4);

  <A1, A2, A3, A4, A5, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func5<A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5);

  <A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  <A1, A2, A3, A4, A5, A6, A7, R> CompletableFuture<R> executeAsync(
      Class<A1> activityInterface,
      Functions.Func7<A1, A2, A3, A4, A5, A6, A7, R> activity,
      StartActivityOptions options,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      A7 arg7);

  // ---- Handle lookup ----

  /**
   * Returns an untyped handle to an existing standalone activity execution.
   *
   * @param activityId the user-assigned activity ID
   * @param activityRunId the server-assigned run ID, or {@code null} to target any run
   * @return an untyped handle
   */
  UntypedActivityHandle getHandle(String activityId, @Nullable String activityRunId);

  /**
   * Returns a typed handle to an existing standalone activity execution.
   *
   * @param activityId the user-assigned activity ID
   * @param activityRunId the server-assigned run ID, or {@code null} to target any run
   * @param resultClass expected result type
   * @param <R> result type
   * @return a typed handle
   */
  <R> ActivityHandle<R> getHandle(
      String activityId, @Nullable String activityRunId, Class<R> resultClass);

  /**
   * Returns a typed handle to an existing standalone activity execution for generic result types.
   *
   * @param activityId the user-assigned activity ID
   * @param activityRunId the server-assigned run ID, or {@code null} to target any run
   * @param resultClass expected result class
   * @param resultType generic type for deserialisation; may be {@code null}
   * @param <R> result type
   * @return a typed handle
   */
  <R> ActivityHandle<R> getHandle(
      String activityId,
      @Nullable String activityRunId,
      Class<R> resultClass,
      @Nullable Type resultType);

  // ---- List / count ----

  /**
   * Returns a stream of activity executions matching the given query.
   *
   * @param query Temporal visibility query string
   * @return stream of matching activity executions
   */
  Stream<ActivityExecution> listExecutions(String query);

  /**
   * Returns a stream of activity executions matching the given query, with options.
   *
   * @param query Temporal visibility query string
   * @param options list options such as result limit
   * @return stream of matching activity executions
   */
  Stream<ActivityExecution> listExecutions(String query, ActivityListOptions options);

  /**
   * Returns a single page of activity executions matching the given query.
   *
   * @param query Temporal visibility query string
   * @param nextPageToken token from a previous page, or {@code null} for the first page
   * @param options pagination options such as page size
   * @return a page of results and a token for the next page
   */
  ActivityListPage listExecutionsPaginated(
      String query, @Nullable byte[] nextPageToken, ActivityListPaginatedOptions options);

  /**
   * Returns the count of activity executions matching the given query.
   *
   * @param query Temporal visibility query string
   * @return execution count, optionally with aggregation groups
   */
  ActivityExecutionCount countExecutions(String query);

  /**
   * Returns the count of activity executions matching the given query, with options.
   *
   * @param query Temporal visibility query string
   * @param options count options
   * @return execution count, optionally with aggregation groups
   */
  ActivityExecutionCount countExecutions(String query, ActivityCountOptions options);

  /**
   * Creates a new {@link ActivityCompletionClient} for completing activities asynchronously using a
   * task token or activity ID.
   *
   * @return a new completion client
   */
  ActivityCompletionClient newActivityCompletionClient();
}
