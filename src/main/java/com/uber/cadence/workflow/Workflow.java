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
import com.uber.cadence.internal.worker.CheckedExceptionWrapper;
import com.uber.cadence.internal.dispatcher.WorkflowInternal;

import java.time.Duration;

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
    public static Promise<WorkflowExecution> getWorkflowExecution(Object workflowStub) {
        return WorkflowInternal.getWorkflowExecution(workflowStub);
    }

    /**
     * @return context that contains information about currently running workflow.
     */
    public static WorkflowContext getContext() {
        return WorkflowInternal.getContext();
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
        return WorkflowInternal.newTimer(delay);
    }

    public static <E> WorkflowQueue<E> newQueue(int capacity) {
        return WorkflowInternal.newQueue(capacity);
    }

    public static <E> CompletablePromise<E> newCompletablePromise() {
        return WorkflowInternal.newCompletablePromise();
    }

    public static <E> Promise<E> newCompletablePromise(E value) {
        return WorkflowInternal.newPromise(value);
    }

    public static <E> Promise<E> newFailedPromise(Exception failure) {
        return WorkflowInternal.newFailedPromise(CheckedExceptionWrapper.throwWrapped(failure));
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
     * @TODO Provide untyped stub instead the same way CadenceClient provides.
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
     * {@link com.uber.cadence.internal.ActivityException} from calls to an activity and subclass of
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
     * Prohibit instantiation.
     */
    private Workflow() {
    }
}
