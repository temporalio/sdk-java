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

import com.uber.cadence.workflow.ActivitySchedulingOptions;
import com.uber.cadence.workflow.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.WorkflowFuture;
import com.uber.cadence.workflow.WorkflowQueue;
import com.uber.cadence.workflow.WorkflowThread;

import java.lang.reflect.Proxy;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
public final class WorkflowInternal {

    public static WorkflowThread newThread(Functions.Proc runnable) {
        return WorkflowThreadInternal.newThread(runnable);
    }

    public static WorkflowThread newThread(Functions.Proc runnable, String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        return WorkflowThreadInternal.newThread(runnable, name);
    }

    public static WorkflowFuture<Void> newTimer(long delaySeconds) {
        return getDecisionContext().newTimer(delaySeconds);
    }

    public static <E> WorkflowQueue<E> newQueue(int capacity) {
        return new WorkflowQueueImpl<E>(capacity);
    }

    public static <E> WorkflowFuture<E> newFuture() {
        return new WorkflowFutureImpl<>();
    }

    public static <E> WorkflowFuture<E> newFuture(E value) {
        WorkflowFuture result = new WorkflowFutureImpl<>();
        result.complete(value);
        return result;
    }

    public static <E> WorkflowFuture<E> newFailedFuture(Exception failure) {
        WorkflowFuture<E> result = new WorkflowFutureImpl<>();
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
     * Note that workflow executes all threads one at a time, ensures that they are interrupted
     * only when blocked on something like Lock or {@link Future#get()} and uses memory barrier to ensure
     * that all variables are accessible from any thread. So Lock is needed only in rare cases when critical
     * section invokes blocking operations.
     *
     * @return Lock implementation that can be used inside a workflow code.
     */
    public static Lock newReentrantLock() {
        return new LockImpl();
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
    public static <T> T newActivityStub(Class<T> activityInterface, ActivitySchedulingOptions options) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{activityInterface},
                new ActivityInvocationHandler(options));
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
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @return future that contains activity result or failure
     */
    public static <R> WorkflowFuture<R> async(Functions.Func<R> activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (Exception e) {
            return WorkflowInternal.newFailedFuture(e);
        } finally {
            return ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, R> WorkflowFuture<R> async(Functions.Func1<A1, R> activity, A1 arg1) {
        return async(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, R> WorkflowFuture<R> async(Functions.Func2<A1, A2, R> activity, A1 arg1, A2 arg2) {
        return async(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, R> WorkflowFuture<R> async(Functions.Func3<A1, A2, A3, R> activity, A1 arg1, A2 arg2, A3 arg3) {
        return async(() -> activity.apply(arg1, arg2, arg3));
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, R> WorkflowFuture<R> async(Functions.Func4<A1, A2, A3, A4, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4));
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, R> WorkflowFuture<R> async(Functions.Func5<A1, A2, A3, A4, A5, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6, R> WorkflowFuture<R> async(Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @return future that contains activity result or failure
     */
    public static WorkflowFuture<Void> async(Functions.Proc activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (Exception e) {
            return WorkflowInternal.newFailedFuture(e);
        } finally {
            return ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @return future that contains activity result or failure
     */
    public static <A1> WorkflowFuture<Void> async(Functions.Proc1<A1> activity, A1 arg1) {
        return async(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2> WorkflowFuture<Void> async(Functions.Proc2<A1, A2> activity, A1 arg1, A2 arg2) {
        return async(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3> WorkflowFuture<Void> async(Functions.Proc3<A1, A2, A3> activity, A1 arg1, A2 arg2, A3 arg3) {
        return async(() -> activity.apply(arg1, arg2, arg3));
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4> WorkflowFuture<Void> async(Functions.Proc4<A1, A2, A3, A4> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4));
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5> WorkflowFuture<Void> async(Functions.Proc5<A1, A2, A3, A4, A5> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return async(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityStub(Class, ActivitySchedulingOptions)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6> WorkflowFuture<Void> async(Functions.Proc6<A1, A2, A3, A4, A5, A6> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
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
    public static <R> R executeActivity(String name, ActivitySchedulingOptions options, Class<R> returnType, Object... args) {
        return getDecisionContext().executeActivity(name, options, args, returnType);
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
     * @return future that contains the activity result
     */
    public static <R> WorkflowFuture<R> executeActivityAsync(String name, ActivitySchedulingOptions options, Class<R> returnType, Object... args) {
        return getDecisionContext().executeActivityAsync(name, options, args, returnType);
    }

    public static WorkflowThread currentThread() {
        return WorkflowThreadInternal.currentThreadInternal();
    }

    public static boolean currentThreadResetInterrupted() {
        return WorkflowThreadInternal.currentThreadInternal().resetInterrupted();
    }

    public static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws InterruptedException, DestroyWorkflowThreadError {
        return WorkflowThreadInternal.yield(timeoutMillis, reason, unblockCondition);
    }

    /**
     * Prohibit instantiation
     */
    private WorkflowInternal() {

    }
}
