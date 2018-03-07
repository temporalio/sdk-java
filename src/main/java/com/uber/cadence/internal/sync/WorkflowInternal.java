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
package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.replay.ContinueAsNewWorkflowExecutionParameters;
import com.uber.cadence.workflow.CancellationScope;
import com.uber.cadence.workflow.ChildWorkflowOptions;
import com.uber.cadence.workflow.CompletablePromise;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowInfo;
import com.uber.cadence.workflow.WorkflowQueue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import static com.uber.cadence.internal.sync.AsyncInternal.AsyncMarker;

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
public final class WorkflowInternal {

    public static WorkflowThread newThread(boolean ignoreParentCancellation, Runnable runnable) {
        return WorkflowThread.newThread(runnable, ignoreParentCancellation);
    }

    public static WorkflowThread newThread(boolean ignoreParentCancellation, String name, Runnable runnable) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        return WorkflowThread.newThread(runnable, ignoreParentCancellation, name);
    }

    public static Promise<Void> newTimer(Duration duration) {
        return getDecisionContext().newTimer(InternalUtils.roundUpToSeconds(duration).getSeconds());
    }

    public static <E> WorkflowQueue<E> newQueue(int capacity) {
        return new WorkflowQueueImpl<>(capacity);
    }

    public static <E> CompletablePromise<E> newCompletablePromise() {
        return new CompletablePromiseImpl<>();
    }

    public static <E> Promise<E> newPromise(E value) {
        CompletablePromise<E> result = Workflow.newPromise();
        result.complete(value);
        return result;
    }

    public static <E> Promise<E> newFailedPromise(Exception failure) {
        CompletablePromise<E> result = new CompletablePromiseImpl<>();
        result.completeExceptionally(CheckedExceptionWrapper.getWrapped(failure));
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
        return DeterministicRunnerImpl.currentThreadInternal().getRunner().currentTimeMillis();
    }

    /**
     * Creates client stub to activities that implement given interface.
     *
     * @param activityInterface interface type implemented by activities
     */
    @SuppressWarnings("unchecked")
    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{activityInterface, AsyncMarker.class},
                new ActivityInvocationHandler(options));
    }


    @SuppressWarnings("unchecked")
    public static <T> T newWorkflowStubWithOptions(Class<T> workflowInterface, ChildWorkflowOptions options) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface, WorkflowStub.class, AsyncMarker.class},
                new ChildWorkflowInvocationHandler(options, getDecisionContext()));
    }

    @SuppressWarnings("unchecked")
    public static <T> T newWorkflowStubFromExecution(Class<T> workflowInterface, WorkflowExecution execution) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface, WorkflowStub.class, AsyncMarker.class},
                new ChildWorkflowInvocationHandler(execution, getDecisionContext()));
    }

    public static Promise<WorkflowExecution> getChildWorkflowExecution(Object workflowStub) {
        if (workflowStub instanceof WorkflowStub) {
            return ((WorkflowStub) workflowStub).__getWorkflowExecution();
        }
        throw new IllegalArgumentException("Not a workflow stub created through Workflow.newWorkflowStubWithOptions: " + workflowStub);
    }

    /**
     * Creates client stub that can be used to continue this workflow as new.
     *
     * @param workflowInterface interface type implemented by the next generation of workflow
     */
    @SuppressWarnings("unchecked")
    public static <T> T newContinueAsNewStub(Class<T> workflowInterface, ContinueAsNewWorkflowExecutionParameters parameters) {
        return (T) Proxy.newProxyInstance(WorkflowInternal.class.getClassLoader(),
                new Class<?>[]{workflowInterface},
                new ContinueAsNewWorkflowInvocationHandler(parameters, getDecisionContext()));
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
        Promise<R> result = getDecisionContext().executeActivity(name, options, args, returnType);
        if (AsyncInternal.isAsync()) {
            AsyncInternal.setAsyncResult(result);
            return null; // ignored
        }
        return result.get();
    }

    private static SyncDecisionContext getDecisionContext() {
        return DeterministicRunnerImpl.currentThreadInternal().getDecisionContext();
    }

    public static void yield(String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        WorkflowThread.yield(reason, unblockCondition);
    }

    public static boolean yield(long timeoutMillis, String reason, Supplier<Boolean> unblockCondition) throws DestroyWorkflowThreadError {
        return WorkflowThread.yield(timeoutMillis, reason, unblockCondition);
    }

    public static <U> Promise<List<U>> promiseAllOf(Collection<Promise<U>> promises) {
        return new AllOfPromise<>(promises);
    }

    @SuppressWarnings("unchecked")
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
        CancellationScopeImpl result = new CancellationScopeImpl(detached, runnable);
        result.run();
        return result;
    }

    public static CancellationScopeImpl currentCancellationScope() {
        return CancellationScopeImpl.current();
    }


    public static RuntimeException throwWrapped(Throwable e) {
        return CheckedExceptionWrapper.throwWrapped(e);
    }

    public static RuntimeException getWrapped(Throwable e) {
        return CheckedExceptionWrapper.getWrapped(e);
    }

    /**
     * Prohibit instantiation
     */
    private WorkflowInternal() {
    }

    public static boolean isReplaying() {
        return getDecisionContext().isReplaying();
    }

    public static WorkflowInfo getWorkflowInfo() {
       return new WorkflowInfoImpl(getDecisionContext().getContext());
    }

    public static <R> R retry(RetryOptions options, Functions.Func<R> fn) {
        return WorkflowRetryerInternal.validateOptionsAndRetry(options, fn);
    }
}
