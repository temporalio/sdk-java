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
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.workflow.ActivityOptions;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.WorkflowContext;
import com.uber.cadence.workflow.WorkflowQueue;
import com.uber.cadence.workflow.WorkflowThread;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
public final class WorkflowInternal {

    public static WorkflowThread newThread(boolean ignoreParentCancellation, Runnable runnable) {
        return WorkflowThreadInternal.newThread(runnable, ignoreParentCancellation);
    }

    public static WorkflowThread newThread(boolean ignoreParentCancellation, String name, Runnable runnable) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        return WorkflowThreadInternal.newThread(runnable, ignoreParentCancellation, name);
    }

    public static Promise<Void> newTimer(Duration duration) {
        long millis = duration.toMillis();
        float toRound = millis / 1000f;
        long seconds = (long) Math.ceil(toRound);
        return getDecisionContext().newTimer(seconds);
    }

    public static <E> WorkflowQueue<E> newQueue(int capacity) {
        return new WorkflowQueueImpl<E>(capacity);
    }

    public static <E> CompletablePromise<E> newCompletablePromise() {
        return new CompletablePromiseImpl<>();
    }

    public static <E> Promise<E> newPromise(E value) {
        CompletablePromise result = new CompletablePromiseImpl<>();
        result.complete(value);
        return result;
    }

    public static <E> Promise<E> newFailedPromise(RuntimeException failure) {
        CompletablePromise<E> result = new CompletablePromiseImpl<>();
        result.completeExceptionally(failure);
        return result;
    }

    /**
     * Register query or queries implementation object. There is no need to register top level workflow implementation
     * object as it is done implicitly. Only methods annotated with @{@link QueryMethod} are registered.
     */
    public static void registerQuery(Object queryImplementation) {
        getDecisionContext().registerQuery(queryImplementation);
    }

    /**
     * Should be used to get current time instead of {@link System#currentTimeMillis()}
     */
    public static long currentTimeMillis() {
        return WorkflowThreadInternal.currentThreadInternal().getRunner().currentTimeMillis();
    }

    /**
     * Creates client stub to activities that implement given interface.
     *
     * @param activityInterface interface type implemented by activities
     */
    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{activityInterface},
                new ActivityInvocationHandler(options));
    }


    public static <T> T newChildWorkflowStub(Class<T> workflowInterface, ChildWorkflowOptions options) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface, WorkflowStub.class},
                new ChildWorkflowInvocationHandler(options, getDecisionContext()));
    }

    public static Promise<WorkflowExecution> getWorkflowExecution(Object workflowStub) {
        if (workflowStub instanceof WorkflowStub) {
            return ((WorkflowStub) workflowStub).__getWorkflowExecution();
        }
        throw new IllegalArgumentException("Not a workflow stub created through Workflow.newChildWorkflowStub: " + workflowStub);
    }

    /**
     * Creates client stub that can be used to continue this workflow as new.
     *
     * @param workflowInterface interface type implemented by the next generation of workflow
     */
    public static <T> T newContinueAsNewStub(Class<T> workflowInterface, ContinueAsNewWorkflowExecutionParameters parameters) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new ContinueAsNewWorkflowInvocationHandler(parameters, getDecisionContext()));
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @return promise that contains activity result or failure
     */
    public static <R> Promise<R> async(Functions.Func<R> activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (RuntimeException e) {
            return WorkflowInternal.newFailedPromise(e);
        } finally {
            return (Promise<R>) ActivityInvocationHandler.getAsyncInvocationResult();
        }
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
        return async(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, R> Promise<R> async(Functions.Func2<A1, A2, R> activity, A1 arg1, A2 arg2) {
        return async(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, R> Promise<R> async(Functions.Func3<A1, A2, A3, R> activity, A1 arg1, A2 arg2, A3 arg3) {
        return async(() -> activity.apply(arg1, arg2, arg3));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, R> Promise<R> async(Functions.Func4<A1, A2, A3, A4, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, R> Promise<R> async(Functions.Func5<A1, A2, A3, A4, A5, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6, R> Promise<R> async(Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @return promise that contains activity result or failure
     */
    public static Promise<Void> async(Functions.Proc activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (RuntimeException e) {
            return WorkflowInternal.newFailedPromise(e);
        } finally {
            return (Promise<Void>) ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @return promise that contains activity result or failure
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2> Promise<Void> async(Functions.Proc2<A1, A2> activity, A1 arg1, A2 arg2) {
        return async(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is a method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivityOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3> Promise<Void> async(Functions.Proc3<A1, A2, A3> activity, A1 arg1, A2 arg2, A3 arg3) {
        return async(() -> activity.apply(arg1, arg2, arg3));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4> Promise<Void> async(Functions.Proc4<A1, A2, A3, A4> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5> Promise<Void> async(Functions.Proc5<A1, A2, A3, A4, A5> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
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
     * @return promise that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6> Promise<Void> async(Functions.Proc6<A1, A2, A3, A4, A5, A6> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5, arg6));
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
        return getDecisionContext().executeActivity(name, options, args, returnType).get();
    }

    private static SyncDecisionContext getDecisionContext() {
        return WorkflowThreadInternal.currentThreadInternal().getDecisionContext();
    }

    /**
     * Execute activity by name asynchronously.
     *
     * @param name       name of the activity
     * @param returnType activity return type
     * @param args       list of activity arguments
     * @param <R>        activity return type
     * @return promise that contains the activity result
     */
    public static <R> Promise<R> executeActivityAsync(String name, ActivityOptions options, Class<R> returnType, Object... args) {
        return getDecisionContext().executeActivity(name, options, args, returnType);
    }

    public static WorkflowThread currentThread() {
        return WorkflowThreadInternal.currentThreadInternal();
    }

    public static boolean currentThreadResetCanceled() {
        return WorkflowThreadInternal.currentThreadInternal().resetCanceled();
    }

    public static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        return WorkflowThreadInternal.yield(timeoutMillis, reason, unblockCondition);
    }

    public static WorkflowContext getContext() {
        return getDecisionContext().getWorkflowContext();
    }

    public static <U> Promise<List<U>> promiseAllOf(Collection<Promise<U>> promises) {
        return new AllOfPromise(promises);
    }

    public static Promise<Void> promiseAllOf(Promise<?>... promises) {
        return new AllOfPromise(promises);
    }

    public static Promise<Object> promiseAnyOf(Iterable<Promise<?>> promises) {
        return CompletablePromiseImpl.promiseAnyOf(promises);
    }

    public static Promise<Object> promiseAnyOf(Promise<?>... promises) {
        return CompletablePromiseImpl.promiseAnyOf(promises);
    }

    public static CancellationScope newCancellationScope(boolean detached, Runnable runnable) {
        CancellationScopeImpl result = new  CancellationScopeImpl(detached, runnable);
        result.run();
        return result;
    }

    public static CancellationScope currentCancellationScope() {
        return CancellationScopeImpl.current();
    }

    /**
     * Prohibit instantiation
     */
    private WorkflowInternal() {
    }

}
