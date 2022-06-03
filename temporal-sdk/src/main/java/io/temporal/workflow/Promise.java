/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.workflow;

import io.temporal.failure.CanceledFailure;
import io.temporal.internal.sync.WorkflowInternal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Contains result of an asynchronous computation. Similar to {@link java.util.concurrent.Future}
 * with the following differences:
 *
 * <ul>
 *   <li>Can be used only inside a Temporal workflow code. Use {@link java.util.concurrent.Future}
 *       and its derivatives to implement activities and workflow starting and querying code.
 *   <li>{@link #get()} doesn't throw InterruptedException. The only way to unblock {@link #get()}
 *       is to complete the Promise
 *   <li>Exceptions passed to {@link CompletablePromise#completeExceptionally(RuntimeException)} are
 *       not wrapped. It is possible as {@link
 *       CompletablePromise#completeExceptionally(RuntimeException)} accepts only runtime
 *       exceptions. So wrapping must be done by the caller of that method.
 *   <li>Promise doesn't directly supports cancellation. Use {@link CancellationScope} to cancel and
 *       handle cancellations. The pattern is that a canceled operation completes its Promise with
 *       {@link CanceledFailure} when canceled.
 *   <li>{@link #handle(Functions.Func2)} and similar callback operations should follow all the same
 *       constraints as other Workflow Code. See "Workflow Implementation Constraints" on {@link
 *       io.temporal.workflow}.
 * </ul>
 */
public interface Promise<V> {

  /**
   * Returns {@code true} if this promise is completed.
   *
   * <p>Completion may be due to normal termination, an exception, or cancellation -- in all of
   * these cases, this method will return {@code true}.
   *
   * @return {@code true} if this promise completed
   */
  boolean isCompleted();

  /**
   * Waits if necessary for the computation to complete or fail, and then returns its result.
   *
   * @return the computed result
   * @throws RuntimeException if the computation failed.
   */
  V get();

  /**
   * Waits if necessary for the computation to complete or fail, and then returns its result. This
   * call is going to throw {@link CanceledFailure} without waiting for this Promise to become
   * ready. Note that in the most situations it is better to let the operation that returned a
   * Promise to perform cleanup and then complete the promise with CanceledException. So calling
   * {@link #get()} on an asynchronous activity or child workflow invocation result is preferable.
   *
   * @return the computed result
   * @throws RuntimeException if the computation failed.
   * @throws CanceledFailure if surrounding @{@link CancellationScope} is canceled.
   */
  V cancellableGet();

  /**
   * Waits if necessary for at most the given time for the computation to complete, and then returns
   * its result, if available.
   *
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return the computed result
   * @throws RuntimeException if the computation failed.
   * @throws TimeoutException if the wait timed out
   */
  V get(long timeout, TimeUnit unit) throws TimeoutException;

  /**
   * Waits if necessary for at most the given time for the computation to complete, and then returns
   * its result, if available. This call is going to throw {@link CanceledFailure} without waiting
   * for this Promise to become ready. Note that in the most situations it is better to let the
   * operation that returned a Promise to perform cleanup and then complete the promise with
   * CanceledException. So calling {@link #get(long, TimeUnit)} on an asynchronous activity or child
   * workflow invocation result is preferable.
   *
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return the computed result
   * @throws RuntimeException if the computation failed.
   * @throws TimeoutException if the wait timed out
   * @throws CanceledFailure if surrounding @{@link CancellationScope} is canceled.
   */
  V cancellableGet(long timeout, TimeUnit unit) throws TimeoutException;

  /**
   * Waits if necessary for the computation to complete or fail, and then returns the failure or
   * null.
   */
  RuntimeException getFailure();

  /**
   * Returns Promise that contains a result of a function. The function is called with the value of
   * this Promise when it is ready. #completeExceptionally is propagated directly to the returned
   * Promise skipping the function.
   *
   * <p>Note that all the constraints of Workflow Implementation Code apply to {@code fn}. See
   * "Workflow Implementation Constraints" on {@link io.temporal.workflow}.
   */
  <U> Promise<U> thenApply(Functions.Func1<? super V, ? extends U> fn);

  /**
   * Returns Promise that contains a result of a function. The function is called with the value of
   * this Promise or with an exception when it is completed. If the function throws a {@link
   * RuntimeException} it fails the resulting promise.
   *
   * <p>Note that all the constraints of Workflow Implementation Code apply to {@code fn}. See
   * "Workflow Implementation Constraints" on {@link io.temporal.workflow}.
   */
  <U> Promise<U> handle(Functions.Func2<? super V, RuntimeException, ? extends U> fn);

  /**
   * Returns a new Promise that, when this promise completes normally, is executed with this promise
   * as the argument to the supplied function.
   *
   * <p>Note that all the constraints of Workflow Implementation Code apply to {@code fn}. See
   * "Workflow Implementation Constraints" on {@link io.temporal.workflow}.
   *
   * @param fn the function returning a new Promise
   * @param <U> the type of the returned CompletionStage's result
   * @return the Promise that completes when fn returned Promise completes.
   */
  <U> Promise<U> thenCompose(Functions.Func1<? super V, ? extends Promise<U>> fn);

  /**
   * Returns a new Promise that, when this promise completes exceptionally, is executed with this
   * promise's exception as the argument to the supplied function. Otherwise, if this promise
   * completes normally, then the returned promise also completes normally with the same value.
   *
   * <p>Note that all the constraints of Workflow Implementation Code apply to {@code fn}. See
   * "Workflow Implementation Constraints" on {@link io.temporal.workflow}.
   *
   * @param fn the function to use to compute the value of the returned CompletionPromise if this
   *     CompletionPromise completed exceptionally
   * @return the new Promise
   */
  Promise<V> exceptionally(Functions.Func1<Throwable, ? extends V> fn);

  /**
   * Returns Promise that becomes completed when all promises in the collection are completed. A
   * single promise failure causes resulting promise to deliver the failure immediately.
   *
   * @param promises promises to wait for.
   * @return Promise that is completed with null when all the argument promises become completed.
   */
  static <V> Promise<Void> allOf(Iterable<Promise<V>> promises) {
    return WorkflowInternal.promiseAllOf(promises);
  }

  /**
   * Returns Promise that becomes completed when all arguments are completed. A single promise
   * failure causes resulting promise to deliver the failure immediately.
   */
  static Promise<Void> allOf(Promise<?>... promises) {
    return WorkflowInternal.promiseAllOf(promises);
  }

  /**
   * Returns Promise that becomes completed when any of the arguments is completed. If it completes
   * exceptionally then result is also completes exceptionally.
   */
  static <V> Promise<V> anyOf(Iterable<Promise<V>> promises) {
    return WorkflowInternal.promiseAnyOf(promises);
  }

  /**
   * Returns Promise that becomes completed when any of the arguments is completed. If it completes
   * exceptionally then result is also completes exceptionally.
   */
  static Promise<Object> anyOf(Promise<?>... promises) {
    return WorkflowInternal.promiseAnyOf(promises);
  }
}
