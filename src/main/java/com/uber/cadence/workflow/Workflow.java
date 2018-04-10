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
import com.uber.cadence.internal.sync.WorkflowInternal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

public final class Workflow {

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities.
   * @param options options that together with the properties of {@link
   *     com.uber.cadence.activity.ActivityMethod} specify the activity invocation parameters.
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
   * Creates client stub to activities that implement given interface.
   *
   * @param options specify the activity invocation parameters.
   */
  public static ActivityStub newUntypedActivityStub(ActivityOptions options) {
    return WorkflowInternal.newUntypedActivityStub(options);
  }

  /**
   * Creates client stub that can be used to start a child workflow that implements given interface
   * using parent options.
   *
   * @param workflowInterface interface type implemented by activities
   */
  public static <T> T newChildWorkflowStub(Class<T> workflowInterface) {
    return WorkflowInternal.newChildWorkflowStub(workflowInterface, null);
  }

  /**
   * Creates client stub that can be used to start a child workflow that implements given interface.
   *
   * @param workflowInterface interface type implemented by activities
   * @param options options passed to the child workflow.
   */
  public static <T> T newChildWorkflowStub(
      Class<T> workflowInterface, ChildWorkflowOptions options) {
    return WorkflowInternal.newChildWorkflowStub(workflowInterface, options);
  }

  /**
   * Creates client stub that can be used to communicate to an existing workflow execution.
   *
   * @param workflowInterface interface type implemented by activities
   * @param workflowId id of the workflow to communicate with.
   */
  public static <R> R newExternalWorkflowStub(
      Class<? extends R> workflowInterface, String workflowId) {
    WorkflowExecution execution = new WorkflowExecution().setWorkflowId(workflowId);
    return WorkflowInternal.newExternalWorkflowStub(workflowInterface, execution);
  }

  /**
   * Creates client stub that can be used to communicate to an existing workflow execution.
   *
   * @param workflowInterface interface type implemented by activities
   * @param execution execution of the workflow to communicate with.
   */
  public static <R> R newExternalWorkflowStub(
      Class<? extends R> workflowInterface, WorkflowExecution execution) {
    return WorkflowInternal.newExternalWorkflowStub(workflowInterface, execution);
  }

  /**
   * Extracts workflow execution from a stub created through {@link #newChildWorkflowStub(Class,
   * ChildWorkflowOptions)} or {@link #newExternalWorkflowStub(Class, String)}. Wrapped in a Promise
   * as child workflow start is asynchronous.
   */
  public static Promise<WorkflowExecution> getWorkflowExecution(Object childWorkflowStub) {
    return WorkflowInternal.getChildWorkflowExecution(childWorkflowStub);
  }

  /**
   * Creates untyped client stub that can be used to start and signal a child workflow.
   *
   * @param workflowType name of the workflow type to start.
   * @param options options passed to the child workflow.
   */
  public static ChildWorkflowStub newUntypedChildWorkflowStub(
      String workflowType, ChildWorkflowOptions options) {
    return WorkflowInternal.newUntypedChildWorkflowStub(workflowType, options);
  }

  /**
   * Creates untyped client stub that can be used to start and signal a child workflow. All options
   * are inherited from the parent.
   *
   * @param workflowType name of the workflow type to start.
   */
  public static ChildWorkflowStub newUntypedChildWorkflowStub(String workflowType) {
    return WorkflowInternal.newUntypedChildWorkflowStub(workflowType, null);
  }

  /**
   * Creates untyped client stub that can be used to signal or cancel a child workflow.
   *
   * @param execution execution of the workflow to communicate with.
   */
  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(WorkflowExecution execution) {
    return WorkflowInternal.newUntypedExternalWorkflowStub(execution);
  }

  /**
   * Creates untyped client stub that can be used to signal or cancel a child workflow.
   *
   * @param workflowId id of the workflow to communicate with.
   */
  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(String workflowId) {
    WorkflowExecution execution = new WorkflowExecution().setWorkflowId(workflowId);
    return Workflow.newUntypedExternalWorkflowStub(execution);
  }

  /**
   * Creates a client stub that can be used to continue this workflow as a new run.
   *
   * @param workflowInterface an interface type implemented by the next run of the workflow
   */
  public static <T> T newContinueAsNewStub(
      Class<T> workflowInterface, ContinueAsNewOptions options) {
    return WorkflowInternal.newContinueAsNewStub(workflowInterface, options);
  }

  /**
   * Creates a client stub that can be used to continue this workflow as a new run.
   *
   * @param workflowInterface an interface type implemented by the next run of the workflow
   */
  public static <T> T newContinueAsNewStub(Class<T> workflowInterface) {
    return WorkflowInternal.newContinueAsNewStub(workflowInterface, null);
  }

  /**
   * Continues the current workflow execution as a new run with the same options.
   *
   * @param args arguments of the next run.
   * @see #newContinueAsNewStub(Class)
   */
  public static void continueAsNew(Object... args) {
    Workflow.continueAsNew(Optional.empty(), Optional.empty(), args);
  }

  /**
   * Continues the current workflow execution as a new run possibly overriding the workflow type and
   * options.
   *
   * @param options option overrides for the next run.
   * @param args arguments of the next run.
   * @see #newContinueAsNewStub(Class)
   */
  public static void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object... args) {
    WorkflowInternal.continueAsNew(workflowType, options, args);
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
   * Create new timer. Note that Cadence service time resolution is in seconds. So all durations are
   * rounded <b>up</b> to the nearest second.
   *
   * @return feature that becomes ready when at least specified number of seconds passes. promise is
   *     failed with {@link java.util.concurrent.CancellationException} if enclosing scope is
   *     cancelled.
   */
  public static Promise<Void> newTimer(Duration delay) {
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
   * Register query or queries implementation object. There is no need to register top level
   * workflow implementation object as it is done implicitly. Only methods annotated with @{@link
   * QueryMethod} are registered.
   */
  public static void registerQuery(Object queryImplementation) {
    WorkflowInternal.registerQuery(queryImplementation);
  }

  /**
   * Must be used to get current time instead of {@link System#currentTimeMillis()} to guarantee
   * determinism.
   */
  public static long currentTimeMillis() {
    return WorkflowInternal.currentTimeMillis();
  }

  /** Must be called instead of {@link Thread#sleep(long)} to guarantee determinism. */
  public static void sleep(Duration duration) {
    WorkflowInternal.sleep(duration);
  }

  /** Must be called instead of {@link Thread#sleep(long)} to guarantee determinism. */
  public static void sleep(long millis) {
    WorkflowInternal.sleep(Duration.ofMillis(millis));
  }

  /**
   * Block current thread until unblockCondition is evaluated to true.
   *
   * @param unblockCondition condition that should return true to indicate that thread should
   *     unblock.
   * @throws CancellationException if thread (or current {@link CancellationScope} was cancelled).
   */
  public static void await(Supplier<Boolean> unblockCondition) {
    WorkflowInternal.await(
        "await",
        () -> {
          CancellationScope.throwCancelled();
          return unblockCondition.get();
        });
  }

  /**
   * Block current workflow thread until unblockCondition is evaluated to true or timeoutMillis
   * passes.
   *
   * @return false if timed out.
   * @throws CancellationException if thread (or current {@link CancellationScope} was cancelled).
   */
  public static boolean await(Duration timeout, Supplier<Boolean> unblockCondition) {
    return WorkflowInternal.await(
        timeout,
        "await",
        () -> {
          CancellationScope.throwCancelled();
          return unblockCondition.get();
        });
  }

  /**
   * Invokes function retrying in case of failures according to retry options. Synchronous variant.
   * Use {@link Async#retry(RetryOptions, Functions.Func)} for asynchronous functions.
   *
   * @param options retry options that specify retry policy
   * @param fn function to invoke and retry
   * @return result of the function or the last failure.
   */
  public static <R> R retry(RetryOptions options, Functions.Func<R> fn) {
    return WorkflowInternal.retry(options, fn);
  }

  /**
   * If there is a need to return a checked exception from a workflow implementation do not add the
   * exception to a method signature but wrap it using this method before rethrowing. The library
   * code will unwrap it automatically using {@link #unwrap(Exception)} when propagating exception
   * to a remote caller. {@link RuntimeException} are just returned from this method without
   * modification.
   *
   * <p>The reason for such design is that returning originally thrown exception from a remote call
   * (which child workflow and activity invocations are ) would not allow adding context information
   * about a failure, like activity and child workflow id. So stubs always throw a subclass of
   * {@link ActivityException} from calls to an activity and subclass of {@link
   * com.uber.cadence.workflow.ChildWorkflowException} from calls to a child workflow. The original
   * exception is attached as a cause to these wrapper exceptions. So as exceptions are always
   * wrapped adding checked ones to method signature causes more pain than benefit.
   *
   * <p>
   *
   * <pre>
   * try {
   *     return someCall();
   * } catch (Exception e) {
   *     throw CheckedExceptionWrapper.wrap(e);
   * }
   * </pre>
   *
   * *
   *
   * @return CheckedExceptionWrapper if e is checked or original exception if e extends
   *     RuntimeException.
   */
  public static RuntimeException wrap(Exception e) {
    return WorkflowInternal.wrap(e);
  }

  /**
   * Removes {@link com.uber.cadence.internal.common.CheckedExceptionWrapper} from causal exception
   * chain.
   *
   * @param e exception with causality chain that might contain wrapped exceptions.
   * @return exception causality chain with CheckedExceptionWrapper removed.
   */
  public static Exception unwrap(Exception e) {
    return WorkflowInternal.unwrap(e);
  }

  /**
   * True if workflow code is being replayed. <b>Warning!</b> Never make workflow logic depend on
   * this flag as it is going to break determinism. The only reasonable uses for this flag are
   * deduping external never failing side effects like logging or metric reporting.
   */
  public static boolean isReplaying() {
    return WorkflowInternal.isReplaying();
  }

  /** Prohibit instantiation. */
  private Workflow() {}
}
