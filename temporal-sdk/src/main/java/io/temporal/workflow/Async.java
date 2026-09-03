package io.temporal.workflow;

import io.temporal.common.RetryOptions;
import io.temporal.internal.sync.AsyncInternal;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Supports invoking lambdas and activity and child workflow references asynchronously. */
public final class Async {

  /**
   * Invokes zero argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @return promise that contains function result or failure
   */
  public static <R> Promise<R> function(Functions.Func<R> function) {
    return AsyncInternal.function(function);
  }

  /**
   * Invokes one argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @return promise that contains function result or failure
   */
  public static <A1, R> Promise<R> function(Functions.Func1<A1, R> function, A1 arg1) {
    return AsyncInternal.function(function, arg1);
  }

  /**
   * Invokes two argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, R> Promise<R> function(
      Functions.Func2<A1, A2, R> function, A1 arg1, A2 arg2) {
    return AsyncInternal.function(function, arg1, arg2);
  }

  /**
   * Invokes three argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, R> Promise<R> function(
      Functions.Func3<A1, A2, A3, R> function, A1 arg1, A2 arg2, A3 arg3) {
    return AsyncInternal.function(function, arg1, arg2, arg3);
  }

  /**
   * Invokes four argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, R> Promise<R> function(
      Functions.Func4<A1, A2, A3, A4, R> function, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4);
  }

  /**
   * Invokes five argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @param arg5 fifth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, A5, R> Promise<R> function(
      Functions.Func5<A1, A2, A3, A4, A5, R> function,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Invokes six argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @param arg5 fifth function argument
   * @param arg6 sixth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, A5, A6, R> Promise<R> function(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> function,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Invokes zero argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @return Promise that contains procedure result or failure
   */
  public static Promise<Void> procedure(Functions.Proc procedure) {
    return AsyncInternal.procedure(procedure);
  }

  /**
   * Invokes one argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1> Promise<Void> procedure(Functions.Proc1<A1> procedure, A1 arg1) {
    return AsyncInternal.procedure(procedure, arg1);
  }

  /**
   * Invokes two argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2> Promise<Void> procedure(
      Functions.Proc2<A1, A2> procedure, A1 arg1, A2 arg2) {
    return AsyncInternal.procedure(procedure, arg1, arg2);
  }

  /**
   * Invokes three argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3> Promise<Void> procedure(
      Functions.Proc3<A1, A2, A3> procedure, A1 arg1, A2 arg2, A3 arg3) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3);
  }

  /**
   * Invokes four argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4> Promise<Void> procedure(
      Functions.Proc4<A1, A2, A3, A4> procedure, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4);
  }

  /**
   * Invokes five argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @param arg5 fifth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4, A5> Promise<Void> procedure(
      Functions.Proc5<A1, A2, A3, A4, A5> procedure, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Invokes six argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @param arg5 fifth procedure argument
   * @param arg6 sixth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4, A5, A6> Promise<Void> procedure(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> procedure,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Returns a promise that completes with {@code true} when {@code unblockCondition} evaluates to
   * {@code true}, or with {@code false} when {@code timeout} expires. Unlike {@link
   * Workflow#await(Duration, Supplier)}, this method does not block the calling workflow thread.
   *
   * <p>The condition is evaluated on every workflow state transition. It must not call blocking
   * operations or mutate workflow state. It must also not contain time-based conditions; use the
   * {@code timeout} parameter for those.
   *
   * <p>If the {@link CancellationScope} active when this method is invoked is canceled, the promise
   * completes exceptionally with a {@link io.temporal.failure.CanceledFailure}. An exception thrown
   * by the condition also completes the promise exceptionally.
   *
   * @param timeout time after which the promise completes with {@code false} if the condition is
   *     not satisfied.
   * @param unblockCondition condition that completes the promise with {@code true} when satisfied.
   * @return promise that contains whether the condition was satisfied before the timeout.
   */
  public static Promise<Boolean> await(Duration timeout, Supplier<Boolean> unblockCondition) {
    return AsyncInternal.await(timeout, unblockCondition);
  }

  /**
   * Invokes function retrying in case of failures according to retry options. Asynchronous variant.
   * Use {@link Workflow#retry(RetryOptions, Optional, Functions.Func)} for synchronous functions.
   *
   * @param options retry options that specify retry policy
   * @param expiration if provided limits duration of retries
   * @param fn function to invoke and retry
   * @return result of the function or the last failure.
   */
  public static <R> Promise<R> retry(
      RetryOptions options, Optional<Duration> expiration, Functions.Func<Promise<R>> fn) {
    return AsyncInternal.retry(options, expiration, fn);
  }

  /** Prohibits instantiation. */
  private Async() {}
}
