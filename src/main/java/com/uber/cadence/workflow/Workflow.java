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

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.internal.sync.WorkflowInternal;

import java.time.Duration;
import java.util.Objects;

public final class Workflow {

    /**
     * Creates client stub to activities that implement given interface.
     *
     * @param activityInterface interface type implemented by activities
     */
    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        return WorkflowInternal.newActivityStub(activityInterface, options);
    }

    /**
     * Creates client stub to activities that implement given interface.
     *
     * @param activityInterface interface type implemented by activities
     */
    public static <T> T newActivityStub(Class<T> activityInterface) {
        return WorkflowInternal.newActivityStub(activityInterface, null);
    }

    /**
     * Creates client stub to a child workflow that implements given interface using
     * parent options.
     *
     * @param workflowInterface interface type implemented by activities
     */
    public static <T> T newChildWorkflowStub(Class<T> workflowInterface) {
        return WorkflowInternal.newChildWorkflowStub(workflowInterface, null);
    }

    /**
     * Creates client stub to a child workflow that implements given interface.
     *
     * @param workflowInterface interface type implemented by activities
     * @param options           options passed to the child workflow.
     */
    public static <T> T newChildWorkflowStub(Class<T> workflowInterface, ChildWorkflowOptions options) {
        return WorkflowInternal.newChildWorkflowStub(workflowInterface, options);
    }

    /**
     * Extracts workflow execution from a stub created through {@link #newChildWorkflowStub(Class, ChildWorkflowOptions)}.
     * Wrapped in a Promise as child workflow start is asynchronous.
     */
    public static Promise<WorkflowExecution> getChildWorkflowExecution(Object childWorkflowStub) {
        return WorkflowInternal.getChildWorkflowExecution(childWorkflowStub);
    }

    public static WorkflowInfo getWorkflowInfo() {
        return WorkflowInternal.getWorkflowInfo();
    }

    public static <R> CancellationScope newCancellationScope(Runnable runnable) {
        return WorkflowInternal.newCancellationScope(false, runnable);
    }

    public static CancellationScope newDetachedCancellationScope(Runnable runnable) {
        return WorkflowInternal.newCancellationScope(true, runnable);
    }

    /**
     * Create new timer.  Note that Cadence service time resolution is in seconds.
     * So all durations are rounded <b>up</b> to the nearest second.
     *
     * @return feature that becomes ready when at least specified number of seconds passes.
     * promise is failed with {@link java.util.concurrent.CancellationException} if enclosing scope is cancelled.
     */
    public static Promise<Void> newTimer(Duration delay) {
        Objects.requireNonNull(delay);
        return WorkflowInternal.newTimer(delay);
    }

    public static <E> WorkflowQueue<E> newQueue(int capacity) {
        return WorkflowInternal.newQueue(capacity);
    }

    public static <E> CompletablePromise<E> newPromise() {
        return WorkflowInternal.newCompletablePromise();
    }

    public static <E> Promise<E> newPromise(E value) {
        return WorkflowInternal.newPromise(value);
    }

    public static <E> Promise<E> newFailedPromise(Exception failure) {
        return WorkflowInternal.newFailedPromise(failure);
    }

    /**
     * Register query or queries implementation object. There is no need to register top level workflow implementation
     * object as it is done implicitly. Only methods annotated with @{@link QueryMethod} are registered.
     */
    public static void registerQuery(Object queryImplementation) {
        WorkflowInternal.registerQuery(queryImplementation);
    }

    /**
     * Should be used to get current time instead of {@link System#currentTimeMillis()}
     */
    public static long currentTimeMillis() {
        return WorkflowInternal.currentTimeMillis();
    }

    public static void sleep(Duration duration) {
        sleep(duration.toMillis());
    }

    public static void sleep(long millis) {
        WorkflowInternal.yield(millis, "sleep", () -> {
            CancellationScope.throwCancelled();
            return false;
        });
    }

    /**
     * Invokes function retrying in case of failures according to retry options.
     * Synchronous variant. Use {@link Async#retry(RetryOptions, Functions.Func)} for asynchronous functions.
     *
     * @param options retry options that specify retry policy
     * @param fn      function to invoke and retry
     * @return result of the function or the last failure.
     */
    public static <R> R retry(RetryOptions options, Functions.Func<R> fn) {
        return WorkflowInternal.retry(options, fn);
    }

    /**
     * Creates client stub that can be used to continue this workflow as new generation.
     *
     * @param workflowInterface interface type implemented by next generation of workflow
     */
    public static <T> T newContinueAsNewStub(Class<T> workflowInterface, ContinueAsNewWorkflowExecutionParameters parameters) {
        return WorkflowInternal.newContinueAsNewStub(workflowInterface, parameters);
    }

    /**
     * Creates client stub that can be used to continue this workflow as new generation.
     *
     * @param workflowInterface interface type implemented by next generation of workflow
     */
    public static <T> T newContinueAsNewStub(Class<T> workflowInterface) {
        return WorkflowInternal.newContinueAsNewStub(workflowInterface, null);
    }

    /**
     * Execute activity by name.
     *
     * @param name       name of the activity
     * @param returnType activity return type
     * @param args       list of activity arguments
     * @param <R>        activity return type
     * @return activity result
     * @TODO Provide untyped stub instead the same way WorkflowClient provides.
     */
    public static <R> R executeActivity(String name, ActivityOptions options, Class<R> returnType, Object... args) {
        return WorkflowInternal.executeActivity(name, options, returnType, args);
    }

    /**
     * If there is a need to return a checked exception from a workflow implementation
     * do not add the exception to a method signature but rethrow it using this method.
     * The library code will unwrap it automatically when propagating exception to the caller.
     * There is no need to wrap unchecked exceptions, but it is safe to call this method on them.
     * <p>
     * The reason for such design is that returning originally thrown exception from a remote call
     * (which child workflow and activity invocations are ) would not allow adding context information about
     * a failure, like activity and child workflow id. So stubs always throw a subclass of
     * {@link ActivityException} from calls to an activity and subclass of
     * {@link com.uber.cadence.workflow.ChildWorkflowException} from calls to a child workflow.
     * The original exception is attached as a cause to these wrapper exceptions. So as exceptions are always wrapped
     * adding checked ones to method signature causes more pain than benefit.
     * </p>
     * <p>
     * Throws original exception if e is {@link RuntimeException} or {@link Error}.
     * Never returns. But return type is not empty to be able to use it as:
     * <pre>
     * try {
     *     return someCall();
     * } catch (Exception e) {
     *     throw CheckedExceptionWrapper.throwWrapped(e);
     * }
     * </pre>
     * If throwWrapped returned void it wouldn't be possible to write <code>throw CheckedExceptionWrapper.throwWrapped</code>
     * and compiler would complain about missing return.
     *
     * @return never returns as always throws.
     */
    public static RuntimeException throwWrapped(Throwable e) {
        return WorkflowInternal.throwWrapped(e);
    }

    /**
     * Similar to throwWrapped, but doesn't throws but returns the wrapped exception.
     * Useful when completing Promise.
     */
    public static RuntimeException getWrapped(Throwable e) {
        return WorkflowInternal.getWrapped(e);
    }

    /**
     * True if workflow code is being replayed.
     * <b>Warning!</b> Never make workflow logic depend on this flag as it is going to break determinism.
     * The only reasonable uses for this flag are deduping external never failing side effects
     * like logging or metric reporting.
     */
    public static boolean isReplaying() {
        return WorkflowInternal.isReplaying();
    }

    /**
     * Prohibit instantiation.
     */
    private Workflow() {
    }
}
