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
 *
 * <p>The primary typed API uses {@code Class<I>} together with unbound method references so the SDK
 * can infer the activity type name and result type automatically:
 *
 * <pre>{@code
 * // Synchronous:
 * String result = client.execute(MyActivity.class, MyActivity::activity, options, 1, 2);
 *
 * // Async handle:
 * ActivityHandle<String> handle = client.start(MyActivity.class, MyActivity::activity, options, 1, 2);
 *
 * // Async future:
 * CompletableFuture<String> fut = client.executeAsync(MyActivity.class, MyActivity::activity, options, 1, 2);
 *
 * // String-based:
 * String result = client.execute("MyActivityType", String.class, options, 1, 2);
 * }</pre>
 */
@Experimental
public interface ActivityClient {

  // ---- Static factories ----

  /** Creates a new {@code ActivityClient} with default options. */
  static ActivityClient newInstance(WorkflowServiceStubs stubs) {
    return newInstance(stubs, ActivityClientOptions.getDefaultInstance());
  }

  /** Creates a new {@code ActivityClient} with the given options. */
  static ActivityClient newInstance(WorkflowServiceStubs stubs, ActivityClientOptions options) {
    return new ActivityClientImpl(stubs, options);
  }

  // ---- Interface-based start (abstract) ----

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference, and
   * returns a typed handle. The activity type name and result type are inferred from the method
   * reference at runtime via a reflection proxy.
   *
   * <p>Example:
   *
   * <pre>{@code
   * ActivityHandle<Void> handle = client.start(MyActivity.class, MyActivity::doWork, options);
   * ActivityHandle<String> handle2 = client.start(MyActivity.class, MyActivity::greet, options, "Alice");
   * }</pre>
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I> ActivityHandle<Void> start(
      Class<I> activityInterface, Functions.Proc1<I> activity, StartActivityOptions options)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc5<I, A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4, A5> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc6<I, A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4, A5, A6> ActivityHandle<Void> start(
      Class<I> activityInterface,
      Functions.Proc7<I, A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with no
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, R> ActivityHandle<R> start(
      Class<I> activityInterface, Functions.Func1<I, R> activity, StartActivityOptions options)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func4<I, A1, A2, A3, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func5<I, A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4, A5, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func6<I, A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5)
      throws ActivityAlreadyStartedException;

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments, returning a typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @param <R> the activity result type
   * @return a typed handle to the started activity
   * @throws ActivityAlreadyStartedException if an activity with the same ID is already running
   */
  <I, A1, A2, A3, A4, A5, A6, R> ActivityHandle<R> start(
      Class<I> activityInterface,
      Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6)
      throws ActivityAlreadyStartedException;

  // ---- Interface-based execute (default) ----

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with no
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I> void execute(
      Class<I> activityInterface, Functions.Proc1<I> activity, StartActivityOptions options)
      throws ActivityFailedException {
    start(activityInterface, activity, options).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1> void execute(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2> void execute(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1, arg2).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3> void execute(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1, arg2, arg3).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4> void execute(
      Class<I> activityInterface,
      Functions.Proc5<I, A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1, arg2, arg3, arg4).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4, A5> void execute(
      Class<I> activityInterface,
      Functions.Proc6<I, A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments, and blocks until it completes.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4, A5, A6> void execute(
      Class<I> activityInterface,
      Functions.Proc7<I, A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6)
      throws ActivityFailedException {
    start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with no
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, R> R execute(
      Class<I> activityInterface, Functions.Func1<I, R> activity, StartActivityOptions options)
      throws ActivityFailedException {
    return start(activityInterface, activity, options).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, R> R execute(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activity,
      StartActivityOptions options,
      A1 arg1)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, R> R execute(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1, arg2).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, R> R execute(
      Class<I> activityInterface,
      Functions.Func4<I, A1, A2, A3, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1, arg2, arg3).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4, R> R execute(
      Class<I> activityInterface,
      Functions.Func5<I, A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4, A5, R> R execute(
      Class<I> activityInterface,
      Functions.Func6<I, A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5).getResult();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments, blocks until it completes, and returns the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @param <R> the activity result type
   * @return the activity result
   * @throws ActivityFailedException if the activity failed, timed out, or was cancelled
   */
  default <I, A1, A2, A3, A4, A5, A6, R> R execute(
      Class<I> activityInterface,
      Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6)
      throws ActivityFailedException {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
        .getResult();
  }

  // ---- Interface-based executeAsync (default) ----

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with no
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @return a future that completes when the activity completes
   */
  default <I> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface, Functions.Proc1<I> activity, StartActivityOptions options) {
    return start(activityInterface, activity, options).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc2<I, A1> activity,
      StartActivityOptions options,
      A1 arg1) {
    return start(activityInterface, activity, options, arg1).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1, A2> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc3<I, A1, A2> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2) {
    return start(activityInterface, activity, options, arg1, arg2).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1, A2, A3> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc4<I, A1, A2, A3> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3) {
    return start(activityInterface, activity, options, arg1, arg2, arg3).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1, A2, A3, A4> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc5<I, A1, A2, A3, A4> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1, A2, A3, A4, A5> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc6<I, A1, A2, A3, A4, A5> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5)
        .getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments, and returns a future that completes when the activity does.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @return a future that completes when the activity completes
   */
  default <I, A1, A2, A3, A4, A5, A6> CompletableFuture<Void> executeAsync(
      Class<I> activityInterface,
      Functions.Proc7<I, A1, A2, A3, A4, A5, A6> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
        .getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with no
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with no activity arguments
   * @param options scheduling options
   * @param <I> the activity interface type
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface, Functions.Func1<I, R> activity, StartActivityOptions options) {
    return start(activityInterface, activity, options).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with one
   * activity argument, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with one activity argument
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func2<I, A1, R> activity,
      StartActivityOptions options,
      A1 arg1) {
    return start(activityInterface, activity, options, arg1).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with two
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with two activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, A2, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func3<I, A1, A2, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2) {
    return start(activityInterface, activity, options, arg1, arg2).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with three
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with three activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, A2, A3, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func4<I, A1, A2, A3, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3) {
    return start(activityInterface, activity, options, arg1, arg2, arg3).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with four
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with four activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, A2, A3, A4, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func5<I, A1, A2, A3, A4, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4).getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with five
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with five activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, A2, A3, A4, A5, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func6<I, A1, A2, A3, A4, A5, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5)
        .getResultAsync();
  }

  /**
   * Starts a standalone activity using a typed interface and an unbound method reference with six
   * activity arguments, and returns a future that resolves to the typed result.
   *
   * @param activityInterface the activity interface class
   * @param activity unbound method reference with six activity arguments
   * @param options scheduling options
   * @param arg1 first activity argument
   * @param arg2 second activity argument
   * @param arg3 third activity argument
   * @param arg4 fourth activity argument
   * @param arg5 fifth activity argument
   * @param arg6 sixth activity argument
   * @param <I> the activity interface type
   * @param <A1> the type of the first activity argument
   * @param <A2> the type of the second activity argument
   * @param <A3> the type of the third activity argument
   * @param <A4> the type of the fourth activity argument
   * @param <A5> the type of the fifth activity argument
   * @param <A6> the type of the sixth activity argument
   * @param <R> the activity result type
   * @return a future that resolves to the activity result
   */
  default <I, A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> executeAsync(
      Class<I> activityInterface,
      Functions.Func7<I, A1, A2, A3, A4, A5, A6, R> activity,
      StartActivityOptions options,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
        .getResultAsync();
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
  Stream<ActivityExecutionMetadata> listExecutions(String query);

  /**
   * Returns a stream of activity executions matching the given query, with options.
   *
   * @param query Temporal visibility query string
   * @param options list options such as result limit
   * @return stream of matching activity executions
   */
  Stream<ActivityExecutionMetadata> listExecutions(String query, ActivityListOptions options);

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
