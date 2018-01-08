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

import java.lang.reflect.Proxy;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;

public class Workflow {

    public static WorkflowThread newThread(Runnable runnable) {
        return WorkflowThreadImpl.newThread(runnable);
    }

    public static WorkflowThread newThread(Runnable runnable, String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        return WorkflowThreadImpl.newThread(runnable, name);
    }

    public static WorkflowFuture<Void> newTimer(long delaySeconds) {
        return getDecisionContext().newTimer(delaySeconds);
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
        return WorkflowThreadImpl.currentThread().getRunner().currentTimeMillis();
    }

    /**
     * Creates client proxy to activities that implement given interface.
     *
     * @param activityInterface interface type implemented by activities
     */
    public static <T> T newActivityClient(Class<T> activityInterface) {
        return (T) Proxy.newProxyInstance(Workflow.class.getClassLoader(),
                new Class<?>[]{activityInterface},
                new ActivityInvocationHandler());
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @return future that contains activity result or failure
     */
    public static <R> WorkflowFuture<R> executeAsync(Functions.Func<R> activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (Exception e) {
            return new WorkflowFuture<R>(e);
        } finally {
            return ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, R> WorkflowFuture<R> executeAsync(Functions.Func1<A1, R> activity, A1 arg1) {
        return executeAsync(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, R> WorkflowFuture<R> executeAsync(Functions.Func2<A1, A2, R> activity, A1 arg1, A2 arg2) {
        return executeAsync(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, R> WorkflowFuture<R> executeAsync(Functions.Func3<A1, A2, A3, R> activity, A1 arg1, A2 arg2, A3 arg3) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3));
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, R> WorkflowFuture<R> executeAsync(Functions.Func4<A1, A2, A3, A4, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4));
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, R> WorkflowFuture<R> executeAsync(Functions.Func5<A1, A2, A3, A4, A5, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6, R> WorkflowFuture<R> executeAsync(Functions.Func6<A1, A2, A3, A4, A5, A6, R> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }

    /**
     * Invokes zero argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @return future that contains activity result or failure
     */
    public static WorkflowFuture<Void> executeAsync(Functions.Proc activity) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply();
        } catch (Exception e) {
            return new WorkflowFuture<>(e);
        } finally {
            return ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    /**
     * Invokes one argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @return future that contains activity result or failure
     */
    public static <A1> WorkflowFuture<Void> executeAsync(Functions.Proc1<A1> activity, A1 arg1) {
        return executeAsync(() -> activity.apply(arg1));
    }

    /**
     * Invokes two argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2> WorkflowFuture<Void> executeAsync(Functions.Proc2<A1, A2> activity, A1 arg1, A2 arg2) {
        return executeAsync(() -> activity.apply(arg1, arg2));
    }

    /**
     * Invokes three argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3> WorkflowFuture<Void> executeAsync(Functions.Proc3<A1, A2, A3> activity, A1 arg1, A2 arg2, A3 arg3) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3));
    }

    /**
     * Invokes four argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4> WorkflowFuture<Void> executeAsync(Functions.Proc4<A1, A2, A3, A4> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4));
    }

    /**
     * Invokes five argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5> WorkflowFuture<Void> executeAsync(Functions.Proc5<A1, A2, A3, A4, A5> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4, arg5));
    }

    /**
     * Invokes six argument activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg1     first activity argument
     * @param arg2     second activity argument
     * @param arg3     third activity argument
     * @param arg4     forth activity argument
     * @param arg5     fifth activity argument
     * @param arg6     sixth activity argument
     * @return future that contains activity result or failure
     */
    public static <A1, A2, A3, A4, A5, A6> WorkflowFuture<Void> executeAsync(Functions.Proc6<A1, A2, A3, A4, A5, A6> activity, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6) {
        return executeAsync(() -> activity.apply(arg1, arg2, arg3, arg4, arg5, arg6));
    }

    /**
     * Execute activity by name.
     * @param name name of the activity
     * @param returnType activity return type
     * @param args list of activity arguments
     * @param <R> activity return type
     * @return activity result
     */
    public static <R> R executeActivity(String name, Class<R> returnType, Object... args) {
        return getDecisionContext().executeActivity(name, args, returnType);
    }

    private static SyncDecisionContext getDecisionContext() {
        return WorkflowThreadImpl.currentThread().getDecisionContext();
    }

    /**
     * Execute activity by name asynchronously.
     * @param name name of the activity
     * @param returnType activity return type
     * @param args list of activity arguments
     * @param <R> activity return type
     * @return future that contains the activity result
     */
    public static <R> WorkflowFuture<R> executeActivityAsync(String name, Class<R> returnType, Object... args) {
        return getDecisionContext().executeActivityAsync(name, args, returnType);
    }

}
