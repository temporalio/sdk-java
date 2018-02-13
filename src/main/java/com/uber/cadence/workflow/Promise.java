/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.workflow;

import com.uber.cadence.internal.dispatcher.WorkflowInternal;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Contains result of an asynchronous computation.
 * Similar to {@link java.util.concurrent.Future} with the following differences:
 * <ul>
 * <li>Can be used only inside a Cadence workflow code. Use {@link java.util.concurrent.Future} and its derivatives
 * to implement activities and workflow starting and querying code.</li>
 * <li>{@link #get()} doesn't throw InterruptedException. The only way to unblock {@link #get()}t is to complete the Promise</li>
 * <li>Exceptions passed to {@link CompletablePromise#completeExceptionally(RuntimeException)} are not wrapped.
 * It is possible as {@link CompletablePromise#completeExceptionally(RuntimeException)} accepts only runtime exceptions.
 * So wrapping must be done by the caller of that method.</li>
 * <li>Promise doesn't directly supports cancellation. Use {@link CancellationScope} to cancel and handle cancellations.
 * The pattern is that a cancelled operation completes its Promise with
 * {@link java.util.concurrent.CancellationException} when cancelled.</li>
 * <li>{@link #handle(Functions.Func2)} and similar callback operations do not allow blocking calls inside functions</li>
 * </ul>
 */
public interface Promise<V> {

    /**
     * Returns {@code true} if this promise is completed.
     * <p>
     * Completion may be due to normal termination, an exception, or
     * cancellation -- in all of these cases, this method will return
     * {@code true}.
     *
     * @return {@code true} if this promise completed
     */
    boolean isCompleted();

    /**
     * Waits if necessary for the computation to complete or fail, and then
     * retrieves its result.
     *
     * @return the computed result
     * @throws RuntimeException if the computation failed.
     */
    V get();

    /**
     * Waits if necessary for the computation to complete or fail, and then
     * retrieves its result or defaultValue in case of failure.
     *
     * @param defaultValue value to return in case of failure
     * @return the computed result
     */
    V get(V defaultValue);

    /**
     * Waits if necessary for at most the given time for the computation
     * to complete, and then retrieves its result, if available.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the computed result
     * @throws RuntimeException if the computation failed.
     * @throws TimeoutException if the wait timed out
     */
    V get(long timeout, TimeUnit unit) throws TimeoutException;

    /**
     * Waits if necessary for at most the given time for the computation
     * to complete, and then retrieves its result, if available.
     *
     * @param timeout      the maximum time to wait
     * @param unit         the time unit of the timeout argument
     * @param defaultValue value to return in case of timeout or failure
     * @return the computed result or default value in case of any failure including timeout.
     */
    V get(long timeout, TimeUnit unit, V defaultValue);

    /**
     * Returns Promise that contains a result of a function. The function is called with the value of this
     * Promise when it is ready. #completeExceptionally is propagated directly to the returned Promise
     * skipping the function.
     * <p>
     * Note that no blocking calls are allowed inside of the function.
     * </p>
     */
    <U> Promise<U> thenApply(Functions.Func1<? super V, ? extends U> fn);

    /**
     * Returns Promise that contains a result of a function. The function is called with the value of this
     * Promise or with an exception when it is completed. If the function throws a {@link RuntimeException} it
     * fails the resulting promise.
     * <p>
     * Note that no blocking calls are allowed inside of the function.
     * </p>
     */
    <U> Promise<U> handle(Functions.Func2<? super V, RuntimeException, ? extends U> fn);

    /**
     * Returns Promise that becomes completed when all promises in the collection are completed.
     * A single promise failure causes resulting promise to deliver the failure immediately.
     *
     * @param promises promises to wait for.
     * @return Promise that contains a list of results of all promises in the same order.
     */
    static <U> Promise<List<U>> allOf(Collection<Promise<U>> promises) {
        return WorkflowInternal.promiseAllOf(promises);
    }

    /**
     * Returns Promise that becomes completed when all arguments a completed.
     * A single promise failure causes resulting promise to deliver the failure immediately.
     */
    static Promise<Void> allOf(Promise<?>... promises) {
        return WorkflowInternal.promiseAllOf(promises);
    }
}
