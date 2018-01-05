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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import java.util.function.Function;

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
     * Invokes activity asynchronously.
     *
     * @param activity The only supported parameter is method reference to a proxy created
     *                 through {@link #newActivityClient(Class)}.
     * @param arg      activity argument
     * @return
     */
    public static <T, R> WorkflowFuture<R> executeAsync(Function<T, R> activity, T arg) {
        ActivityInvocationHandler.initAsyncInvocation();
        try {
            activity.apply(arg);
        } finally {
            return ActivityInvocationHandler.getAsyncInvocationResult();
        }
    }

    public static <T> T executeActivity(String name, Class<T> returnType, Object... args) {
        return WorkflowThreadImpl.currentThread().getDecisionContext().executeActivity(name, args, returnType);
    }
}
