/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.sync;

import static io.temporal.internal.sync.AsyncInternal.AsyncMarker;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.logging.ReplayAwareLogger;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Promise;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.WorkflowQueue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
public final class WorkflowInternal {
  public static final int DEFAULT_VERSION = -1;

  public static WorkflowThread newThread(boolean ignoreParentCancellation, Runnable runnable) {
    return WorkflowThread.newThread(runnable, ignoreParentCancellation);
  }

  public static WorkflowThread newThread(
      boolean ignoreParentCancellation, String name, Runnable runnable) {
    if (name == null) {
      throw new NullPointerException("name cannot be null");
    }
    return WorkflowThread.newThread(runnable, ignoreParentCancellation, name);
  }

  public static Promise<Void> newTimer(Duration duration) {
    return getWorkflowInterceptor().newTimer(duration);
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
    result.completeExceptionally(CheckedExceptionWrapper.wrap(failure));
    return result;
  }

  /**
   * Register query or queries implementation object. There is no need to register top level
   * workflow implementation object as it is done implicitly. Only methods annotated with @{@link
   * QueryMethod} are registered.
   */
  public static void registerListener(Object implementation) {
    Class<?> cls = implementation.getClass();
    POJOWorkflowImplMetadata workflowMetadata = POJOWorkflowImplMetadata.newListenerInstance(cls);
    for (String queryType : workflowMetadata.getQueryTypes()) {
      POJOWorkflowMethodMetadata methodMetadata =
          workflowMetadata.getQueryMethodMetadata(queryType);
      Method method = methodMetadata.getWorkflowMethod();
      getWorkflowInterceptor()
          .registerQuery(
              methodMetadata.getName(),
              method.getParameterTypes(),
              method.getGenericParameterTypes(),
              (args) -> {
                try {
                  return method.invoke(implementation, args);
                } catch (Throwable e) {
                  throw CheckedExceptionWrapper.wrap(e);
                }
              });
    }
    for (String signalType : workflowMetadata.getSignalTypes()) {
      POJOWorkflowMethodMetadata methodMetadata =
          workflowMetadata.getSignalMethodMetadata(signalType);
      Method method = methodMetadata.getWorkflowMethod();
      getWorkflowInterceptor()
          .registerSignal(
              methodMetadata.getName(),
              method.getParameterTypes(),
              method.getGenericParameterTypes(),
              (args) -> {
                try {
                  method.invoke(implementation, args);
                } catch (Throwable e) {
                  throw CheckedExceptionWrapper.wrap(e);
                }
              });
    }
  }

  /** Should be used to get current time instead of {@link System#currentTimeMillis()} */
  public static long currentTimeMillis() {
    return getWorkflowInterceptor().currentTimeMillis();
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   */
  public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
    InvocationHandler invocationHandler =
        ActivityInvocationHandler.newInstance(
            activityInterface, options, WorkflowInternal.getWorkflowInterceptor());
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  /**
   * Creates client stub to local activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   */
  public static <T> T newLocalActivityStub(
      Class<T> activityInterface, LocalActivityOptions options) {
    InvocationHandler invocationHandler =
        LocalActivityInvocationHandler.newInstance(
            activityInterface, options, WorkflowInternal.getWorkflowInterceptor());
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  public static ActivityStub newUntypedActivityStub(ActivityOptions options) {
    return ActivityStubImpl.newInstance(options, getWorkflowInterceptor());
  }

  public static ActivityStub newUntypedLocalActivityStub(LocalActivityOptions options) {
    return LocalActivityStubImpl.newInstance(options, getWorkflowInterceptor());
  }

  @SuppressWarnings("unchecked")
  public static <T> T newChildWorkflowStub(
      Class<T> workflowInterface, ChildWorkflowOptions options) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class, AsyncMarker.class},
            new ChildWorkflowInvocationHandler(
                workflowInterface, options, getWorkflowInterceptor()));
  }

  @SuppressWarnings("unchecked")
  public static <T> T newExternalWorkflowStub(
      Class<T> workflowInterface, WorkflowExecution execution) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class, AsyncMarker.class},
            new ExternalWorkflowInvocationHandler(
                workflowInterface, execution, getWorkflowInterceptor()));
  }

  public static Promise<WorkflowExecution> getWorkflowExecution(Object workflowStub) {
    if (workflowStub instanceof StubMarker) {
      Object stub = ((StubMarker) workflowStub).__getUntypedStub();
      return ((ChildWorkflowStub) stub).getExecution();
    }
    throw new IllegalArgumentException(
        "Not a workflow stub created through Workflow.newChildWorkflowStub: " + workflowStub);
  }

  public static ChildWorkflowStub newUntypedChildWorkflowStub(
      String workflowType, ChildWorkflowOptions options) {
    return new ChildWorkflowStubImpl(workflowType, options, getWorkflowInterceptor());
  }

  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(WorkflowExecution execution) {
    return new ExternalWorkflowStubImpl(execution, getWorkflowInterceptor());
  }

  /**
   * Creates client stub that can be used to continue this workflow as new.
   *
   * @param workflowInterface interface type implemented by the next generation of workflow
   */
  @SuppressWarnings("unchecked")
  public static <T> T newContinueAsNewStub(
      Class<T> workflowInterface, ContinueAsNewOptions options) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface},
            new ContinueAsNewWorkflowInvocationHandler(
                workflowInterface, options, getWorkflowInterceptor()));
  }

  /**
   * Execute activity by name.
   *
   * @param name name of the activity
   * @param resultClass activity return type
   * @param args list of activity arguments
   * @param <R> activity return type
   * @return activity result
   */
  public static <R> R executeActivity(
      String name, ActivityOptions options, Class<R> resultClass, Type resultType, Object... args) {
    Promise<R> result =
        getWorkflowInterceptor().executeActivity(name, resultClass, resultType, args, options);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return null; // ignored
    }
    return result.get();
  }

  private static WorkflowOutboundCallsInterceptor getWorkflowInterceptor() {
    return DeterministicRunnerImpl.currentThreadInternal()
        .getWorkflowContext()
        .getWorkflowInterceptor();
  }

  static SyncWorkflowContext getRootWorkflowContext() {
    return DeterministicRunnerImpl.currentThreadInternal().getWorkflowContext();
  }

  public static void await(String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    getWorkflowInterceptor().await(reason, unblockCondition);
  }

  public static boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    return getWorkflowInterceptor().await(timeout, reason, unblockCondition);
  }

  public static <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    return getWorkflowInterceptor().sideEffect(resultClass, resultType, func);
  }

  public static <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    return getWorkflowInterceptor().mutableSideEffect(id, resultClass, resultType, updated, func);
  }

  public static int getVersion(String changeId, int minSupported, int maxSupported) {
    return getWorkflowInterceptor().getVersion(changeId, minSupported, maxSupported);
  }

  public static Promise<Void> promiseAllOf(Iterable<Promise<?>> promises) {
    return new AllOfPromise(promises);
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
    return new CancellationScopeImpl(detached, runnable);
  }

  public static CancellationScope newCancellationScope(
      boolean detached, Functions.Proc1<CancellationScope> proc) {
    return new CancellationScopeImpl(detached, proc);
  }

  public static CancellationScopeImpl currentCancellationScope() {
    return CancellationScopeImpl.current();
  }

  public static RuntimeException wrap(Throwable e) {
    return CheckedExceptionWrapper.wrap(e);
  }

  public static Exception unwrap(Exception e) {
    return CheckedExceptionWrapper.unwrap(e);
  }

  /** Prohibit instantiation */
  private WorkflowInternal() {}

  /** Returns false if not under workflow code. */
  public static boolean isReplaying() {
    Optional<WorkflowThread> thread = DeterministicRunnerImpl.currentThreadInternalIfPresent();
    return thread.isPresent() && getRootWorkflowContext().isReplaying();
  }

  public static WorkflowInfo getWorkflowInfo() {
    return new WorkflowInfoImpl(getRootWorkflowContext().getContext());
  }

  public static <R> R retry(
      RetryOptions options, Optional<Duration> expiration, Functions.Func<R> fn) {
    return WorkflowRetryerInternal.validateOptionsAndRetry(options, expiration, fn);
  }

  public static void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args) {
    getWorkflowInterceptor().continueAsNew(workflowType, options, args);
  }

  public static void continueAsNew(
      Optional<String> workflowType,
      Optional<ContinueAsNewOptions> options,
      Object[] args,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor) {
    outboundCallsInterceptor.continueAsNew(workflowType, options, args);
  }

  public static Promise<Void> cancelWorkflow(WorkflowExecution execution) {
    return getWorkflowInterceptor().cancelWorkflow(execution);
  }

  public static void sleep(Duration duration) {
    getWorkflowInterceptor().sleep(duration);
  }

  public static Scope getMetricsScope() {
    return getRootWorkflowContext().getMetricsScope();
  }

  private static boolean isLoggingEnabledInReplay() {
    return getRootWorkflowContext().isLoggingEnabledInReplay();
  }

  public static UUID randomUUID() {
    return getRootWorkflowContext().randomUUID();
  }

  public static Random newRandom() {
    return getRootWorkflowContext().newRandom();
  }

  public static Logger getLogger(Class<?> clazz) {
    Logger logger = LoggerFactory.getLogger(clazz);
    return new ReplayAwareLogger(
        logger, WorkflowInternal::isReplaying, WorkflowInternal::isLoggingEnabledInReplay);
  }

  public static Logger getLogger(String name) {
    Logger logger = LoggerFactory.getLogger(name);
    return new ReplayAwareLogger(
        logger, WorkflowInternal::isReplaying, WorkflowInternal::isLoggingEnabledInReplay);
  }

  public static <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    return getRootWorkflowContext().getLastCompletionResult(resultClass, resultType);
  }

  public static void upsertSearchAttributes(Map<String, Object> searchAttributes) {
    getWorkflowInterceptor().upsertSearchAttributes(searchAttributes);
  }

  public static DataConverter getDataConverter() {
    return getRootWorkflowContext().getDataConverter();
  }

  /**
   * Name of the workflow type the interface defines. It is either the interface short name * or
   * value of {@link WorkflowMethod#name()} parameter.
   *
   * @param workflowInterfaceClass interface annotated with @WorkflowInterface
   */
  public static String getWorkflowType(Class<?> workflowInterfaceClass) {
    POJOWorkflowInterfaceMetadata metadata =
        POJOWorkflowInterfaceMetadata.newInstance(workflowInterfaceClass);
    return metadata.getWorkflowType().get();
  }
}
