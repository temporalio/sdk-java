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

package io.temporal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.WorkflowExecutionHistory;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.sync.SyncActivityWorker;
import io.temporal.internal.sync.SyncWorkflowWorker;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.internal.worker.PollerOptions;
import io.temporal.internal.worker.ShutdownManager;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.Suspendable;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts activity and workflow implementations. Uses long poll to receive activity and workflow
 * tasks and processes them in a correspondent thread pool.
 */
public final class Worker implements Suspendable {

  private final WorkerOptions options;
  private final String taskQueue;
  final SyncWorkflowWorker workflowWorker;
  final SyncActivityWorker activityWorker;
  private final AtomicBoolean started = new AtomicBoolean();
  private final Scope metricsScope;

  /**
   * Creates worker that connects to an instance of the Temporal Service.
   *
   * @param client client to the Temporal Service endpoint.
   * @param taskQueue task queue name worker uses to poll. It uses this name for both workflow and
   *     activity task queue polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @param stickyTaskQueueName
   * @param workflowThreadPool thread pool to be used for workflow method threads
   */
  Worker(
      WorkflowClient client,
      String taskQueue,
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      Scope metricsScope,
      WorkflowExecutorCache cache,
      String stickyTaskQueueName,
      ThreadPoolExecutor workflowThreadPool,
      List<ContextPropagator> contextPropagators) {

    Objects.requireNonNull(client, "client should not be null");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskQueue), "taskQueue should not be an empty string");
    this.taskQueue = taskQueue;
    this.options = WorkerOptions.newBuilder(options).validateAndBuildWithDefaults();
    factoryOptions = WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();
    WorkflowServiceStubs service = client.getWorkflowServiceStubs();
    WorkflowClientOptions clientOptions = client.getOptions();
    String namespace = clientOptions.getNamespace();
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1).put(MetricsTag.TASK_QUEUE, taskQueue).build();
    this.metricsScope = metricsScope.tagged(tags);
    SingleWorkerOptions activityOptions =
        toActivityOptions(
            factoryOptions, this.options, clientOptions, contextPropagators, this.metricsScope);
    if (this.options.isLocalActivityWorkerOnly()) {
      activityWorker = null;
    } else {
      activityWorker =
          new SyncActivityWorker(
              service,
              namespace,
              taskQueue,
              this.options.getMaxTaskQueueActivitiesPerSecond(),
              factoryOptions.getWorkerInterceptors(),
              activityOptions);
    }

    SingleWorkerOptions singleWorkerOptions =
        toWorkflowWorkerOptions(
            factoryOptions,
            this.options,
            clientOptions,
            taskQueue,
            contextPropagators,
            this.metricsScope);
    SingleWorkerOptions localActivityOptions =
        toLocalActivityOptions(
            factoryOptions, this.options, clientOptions, contextPropagators, this.metricsScope);
    workflowWorker =
        new SyncWorkflowWorker(
            service,
            namespace,
            taskQueue,
            factoryOptions.getWorkerInterceptors(),
            singleWorkerOptions,
            localActivityOptions,
            cache,
            stickyTaskQueueName,
            factoryOptions.getWorkflowHostLocalTaskQueueScheduleToStartTimeout(),
            workflowThreadPool);
  }

  private static SingleWorkerOptions toActivityOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators,
      Scope metricsScope) {
    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setMaximumPollRatePerSecond(options.getMaxWorkerActivitiesPerSecond())
                .setPollThreadCount(options.getMaxConcurrentActivityTaskPollers())
                .build())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentActivityExecutionSize())
        .setMetricsScope(metricsScope)
        .build();
  }

  private static SingleWorkerOptions toWorkflowWorkerOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      String taskQueue,
      List<ContextPropagator> contextPropagators,
      Scope metricsScope) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1).put(MetricsTag.TASK_QUEUE, taskQueue).build();
    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setPollThreadCount(options.getMaxConcurrentWorkflowTaskPollers())
                .build())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentWorkflowTaskExecutionSize())
        .setDefaultDeadlockDetectionTimeout(options.getDefaultDeadlockDetectionTimeout())
        .setMetricsScope(metricsScope.tagged(tags))
        .build();
  }

  private static SingleWorkerOptions toLocalActivityOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators,
      Scope metricsScope) {
    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setPollerOptions(PollerOptions.newBuilder().setPollThreadCount(1).build())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentLocalActivityExecutionSize())
        .setMetricsScope(metricsScope)
        .build();
  }

  private static SingleWorkerOptions.Builder toSingleWorkerOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators) {
    return SingleWorkerOptions.newBuilder()
        .setDataConverter(clientOptions.getDataConverter())
        .setIdentity(clientOptions.getIdentity())
        .setBinaryChecksum(clientOptions.getBinaryChecksum())
        .setEnableLoggingInReplay(factoryOptions.isEnableLoggingInReplay())
        .setContextPropagators(contextPropagators)
        .setMaxHeartbeatThrottleInterval(options.getMaxHeartbeatThrottleInterval())
        .setDefaultHeartbeatThrottleInterval(options.getDefaultHeartbeatThrottleInterval());
  }

  /**
   * Registers workflow implementation classes with a worker. Can be called multiple times to add
   * more types. A workflow implementation class must implement at least one interface with a method
   * annotated with {@link WorkflowMethod}. By default the short name of the interface is used as a
   * workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>Use {@link io.temporal.workflow.DynamicWorkflow} implementation to implement many workflow
   * types dynamically. It can be useful for implementing DSL based workflows. Only a single type
   * that implements DynamicWorkflow can be registered per worker.
   */
  public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
    Preconditions.checkState(
        !started.get(),
        "registerWorkflowImplementationTypes is not allowed after worker has started");

    workflowWorker.registerWorkflowImplementationTypes(
        WorkflowImplementationOptions.newBuilder().build(), workflowImplementationClasses);
  }

  /**
   * Registers workflow implementation classes with a worker. Can be called multiple times to add
   * more types. A workflow implementation class must implement at least one interface with a method
   * annotated with {@link WorkflowMethod}. By default the short name of the interface is used as a
   * workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>Use {@link io.temporal.workflow.DynamicWorkflow} implementation to implement many workflow
   * types dynamically. It can be useful for implementing DSL based workflows. Only a single type
   * that implements DynamicWorkflow can be registered per worker.
   */
  public void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses) {
    Preconditions.checkState(
        !started.get(),
        "registerWorkflowImplementationTypes is not allowed after worker has started");

    workflowWorker.registerWorkflowImplementationTypes(options, workflowImplementationClasses);
  }

  /**
   * Configures a factory to use when an instance of a workflow implementation is created.
   * !IMPORTANT to provide newly created instances, each time factory is applied.
   *
   * <p>Unless mocking a workflow execution use {@link
   * #registerWorkflowImplementationTypes(Class[])}.
   *
   * @param workflowInterface Workflow interface that this factory implements
   * @param factory factory that when called creates a new instance of the workflow implementation
   *     object.
   * @param <R> type of the workflow object to create.
   */
  public <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> workflowInterface, Func<R> factory) {
    workflowWorker.addWorkflowImplementationFactory(options, workflowInterface, factory);
  }

  /**
   * Configures a factory to use when an instance of a workflow implementation is created. The only
   * valid use for this method is unit testing, specifically to instantiate mocks that implement
   * child workflows. An example of mocking a child workflow:
   *
   * <pre><code>
   *   worker.addWorkflowImplementationFactory(ChildWorkflow.class, () -&gt; {
   *     ChildWorkflow child = mock(ChildWorkflow.class);
   *     when(child.workflow(anyString(), anyString())).thenReturn("result1");
   *     return child;
   *   });
   * </code></pre>
   *
   * <p>Unless mocking a workflow execution use {@link
   * #registerWorkflowImplementationTypes(Class[])}.
   *
   * @param workflowInterface Workflow interface that this factory implements
   * @param factory factory that when called creates a new instance of the workflow implementation
   *     object.
   * @param <R> type of the workflow object to create.
   */
  @VisibleForTesting
  public <R> void addWorkflowImplementationFactory(Class<R> workflowInterface, Func<R> factory) {
    workflowWorker.addWorkflowImplementationFactory(workflowInterface, factory);
  }

  /**
   * Register activity implementation objects with a worker. An implementation object can implement
   * one or more activity types.
   *
   * <p>An activity implementation object must implement at least one interface annotated with
   * {@link io.temporal.activity.ActivityInterface}. Each method of the annotated interface becomes
   * an activity type.
   *
   * <p>Implementations that share a worker must implement different interfaces as an activity type
   * is identified by the activity interface, not by the implementation.
   *
   * <p>Use an implementation of {@link io.temporal.activity.DynamicActivity} to register an object
   * that can implement activity types dynamically. A single registration of DynamicActivity
   * implementation per worker is allowed.
   */
  public void registerActivitiesImplementations(Object... activityImplementations) {
    Preconditions.checkState(
        !started.get(),
        "registerActivitiesImplementations is not allowed after worker has started");

    if (activityWorker != null) {
      activityWorker.registerActivityImplementations(activityImplementations);
    }
    workflowWorker.registerLocalActivityImplementations(activityImplementations);
  }

  void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    workflowWorker.start();
    if (activityWorker != null) {
      activityWorker.start();
    }
  }

  CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptUserTasks) {
    CompletableFuture<Void> workflowWorkerShutdownFuture =
        workflowWorker.shutdown(shutdownManager, interruptUserTasks);
    if (activityWorker != null) {
      return CompletableFuture.allOf(
          activityWorker.shutdown(shutdownManager, interruptUserTasks),
          workflowWorkerShutdownFuture);
    } else {
      return workflowWorkerShutdownFuture;
    }
  }

  boolean isTerminated() {
    boolean isTerminated = workflowWorker.isTerminated();
    if (activityWorker != null) {
      isTerminated = activityWorker.isTerminated();
    }
    return isTerminated;
  }

  void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = timeout;
    if (activityWorker != null) {
      timeoutMillis = InternalUtils.awaitTermination(activityWorker, unit.toMillis(timeout));
    }
    InternalUtils.awaitTermination(workflowWorker, timeoutMillis);
  }

  @Override
  public String toString() {
    return "Worker{" + "options=" + options + '}';
  }

  /**
   * This is a utility method to replay a workflow execution using this particular instance of a
   * worker. This method is useful for troubleshooting workflows by running them in a debugger. The
   * workflow implementation type must be already registered with this worker for this method to
   * work.
   *
   * <p>There is no need to call {@link #start()} to be able to call this method <br>
   * The worker doesn't have to be registered on the same task queue as the execution in the
   * history. <br>
   * This method shouldn't be a part of normal production usage. It's intended for testing and
   * debugging only.
   *
   * @param history workflow execution history to replay
   * @throws Exception if replay failed for any reason
   */
  @VisibleForTesting
  public void replayWorkflowExecution(WorkflowExecutionHistory history) throws Exception {
    workflowWorker.queryWorkflowExecution(
        history,
        WorkflowClient.QUERY_TYPE_REPLAY_ONLY,
        String.class,
        String.class,
        new Object[] {});
  }

  /**
   * This is an utility method to replay a workflow execution using this particular instance of a
   * worker. This method is useful to troubleshoot workflows by running them in a debugger. To work
   * the workflow implementation type must be registered with this worker. There is no need to call
   * {@link #start()} to be able to call this method.
   *
   * @param jsonSerializedHistory workflow execution history in JSON format to replay
   * @throws Exception if replay failed for any reason
   */
  public void replayWorkflowExecution(String jsonSerializedHistory) throws Exception {
    WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(jsonSerializedHistory);
    replayWorkflowExecution(history);
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  @Override
  public void suspendPolling() {
    workflowWorker.suspendPolling();
    if (activityWorker != null) {
      activityWorker.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    workflowWorker.resumePolling();
    if (activityWorker != null) {
      activityWorker.resumePolling();
    }
  }

  @Override
  public boolean isSuspended() {
    boolean isSuspended = workflowWorker.isSuspended();
    if (activityWorker != null) {
      isSuspended = isSuspended && activityWorker.isSuspended();
    }
    return isSuspended;
  }

  /**
   * Name of the workflow type the interface defines. It is either the interface short name or value
   * of {@link WorkflowMethod#name()} parameter.
   *
   * @param workflowInterfaceClass interface annotated with @WorkflowInterface
   */
  public static String getWorkflowType(Class<?> workflowInterfaceClass) {
    return WorkflowInternal.getWorkflowType(workflowInterfaceClass);
  }
}
