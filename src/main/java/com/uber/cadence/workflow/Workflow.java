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
import com.uber.cadence.error.CheckedExceptionWrapper;
import com.uber.cadence.internal.dispatcher.WorkflowInternal;

import java.util.concurrent.TimeUnit;

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
     *W
     * @param workflowInterface interface type implemented by activities
     * @param options options passed to the child workflow.
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

    public static WorkflowThread newThread(Runnable runnable) {
        return WorkflowInternal.newThread(false, runnable);
    }

    public static WorkflowThread newThread(String name, Runnable runnable) {
        return WorkflowInternal.newThread(false, name, runnable);
    }

    public static WorkflowThread newDetachedThread(Runnable runnable) {
        return WorkflowInternal.newThread(true, runnable);
    }

    public static WorkflowThread newDetachedThread(String name, Runnable runnable) {
        return WorkflowInternal.newThread(true, name, runnable);
    }

    public static <R> CancellationScope newCancellationScope(Runnable runnable) {
        return WorkflowInternal.newCancellationScope(false, runnable);
    }

    public static CancellationScope newDetachedCancellationScope(Runnable runnable) {
        return WorkflowInternal.newCancellationScope(true, runnable);
    }

    /**
     * Create new timer.
     *
     * @return feature that becomes ready when at least specified number of seconds passes.
     * promise is failed with {@link java.util.concurrent.CancellationException} if enclosing scope is cancelled.
     */
    public static Promise<Void> newTimer(long delaySeconds) {
        return WorkflowInternal.newTimer(delaySeconds);
    }

    /**
     * Create new timer. Note that time resolution is in seconds.
     * So all partial values are rounded up to the nearest second.
     *
     * @return feature that becomes ready when at least specified number of seconds passes.
     * promise is failed with {@link java.util.concurrent.CancellationException} if enclosing scope is cancelled.
     */
    public static Promise<Void> newTimer(long time, TimeUnit unit) {
        long milliseconds = (long) Math.ceil(unit.toMillis(time) / 1000000f);
        return WorkflowInternal.newTimer(TimeUnit.MILLISECONDS.toSeconds(milliseconds));
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
        return WorkflowInternal.newFailedPromise(CheckedExceptionWrapper.wrap(failure));
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
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @return promise that contains activity result or failure
     */
    public static <R> Promise<R> async(Functions.Func<R> activity) {
        return WorkflowInternal.async(activity);
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @return promise that contains activity result or failure
     */
    public static <A1, R> Promise<R> async(Functions.Func1<A1, R> activity, A1 arg1) {
        return WorkflowInternal.async(activity, arg1);
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, R> Promise<R> async(Functions.Func2<A1, A2, R> activity, A1 arg1, A2 arg2) {
        return WorkflowInternal.async(activity, arg1, arg2);
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, R> Promise<R> async(Functions.Func3<A1, A2, A3, R> activity, A1 arg1, A2 arg2, A3 arg3) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3);
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, R> Promise<R> async(Functions.Func4<A1, A2, A3, A4, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4);
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, R> Promise<R> async(Functions.Func5<A1, A2, A3, A4, A5, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4, arg5);
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6, R> Promise<R> async(Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @return Promise that contains activity result or failure
     */
    public static Promise<Void> async(Functions.Proc activity) {
        return WorkflowInternal.async(activity);
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1> Promise<Void> async(Functions.Proc1<A1> activity, A1 arg1) {
        return async(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2> Promise<Void> async(Functions.Proc2<A1, A2> activity, A1 arg1, A2 arg2) {
        return WorkflowInternal.async(activity, arg1, arg2);
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3> Promise<Void> async(Functions.Proc3<A1, A2, A3> activity, A1 arg1, A2 arg2, A3 arg3) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3);
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4> Promise<Void> async(Functions.Proc4<A1, A2, A3, A4> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4);
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5> Promise<Void> async(Functions.Proc5<A1, A2, A3, A4, A5> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4, arg5);
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return Promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6> Promise<Void> async(Functions.Proc6<A1, A2, A3, A4, A5, A6> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return WorkflowInternal.async(activity, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    /**
     * Execute activity by name.
     *
     * @param name       name of the activity
     * @param returnType activity return type
     * @param args       list of activity arguments
     * @param <R>        activity return type
     * @return activity result
     */
    public static <R> R executeActivity(String name, ActivityOptions options, Class<R> returnType, Object... args) {
        return WorkflowInternal.executeActivity(name, options, returnType, args);
    }

    /**
     * Execute activity by name asynchronously.
     *
     * @param name       name of the activity
     * @param returnType activity return type
     * @param args       list of activity arguments
     * @param <R>        activity return type
     * @return Promise that contains the activity result
     */
    public static <R> Promise<R> executeActivityAsync(String name, ActivityOptions options, Class<R> returnType, Object... args) {
        return WorkflowInternal.executeActivityAsync(name, options, returnType, args);
    }

    /**
     * Prohibit instantiation.
     */
    private Workflow() {
    }
}
