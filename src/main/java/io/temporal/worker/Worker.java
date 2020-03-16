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

package io.temporal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.util.ImmutableMap;
import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.context.ContextPropagator;
import io.temporal.converter.DataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.sync.SyncActivityWorker;
import io.temporal.internal.sync.SyncWorkflowWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.Suspendable;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts activity and workflow implementations. Uses long poll to receive activity and decision
 * tasks and processes them in a correspondent thread pool.
 */
public final class Worker implements Suspendable {

  private final WorkerOptions options;
  private final String taskList;
  private final SyncWorkflowWorker workflowWorker;
  private final SyncActivityWorker activityWorker;
  private final AtomicBoolean started = new AtomicBoolean();
  private final DeciderCache cache;
  private final String stickyTaskListName;
  private ThreadPoolExecutor threadPoolExecutor;

  /**
   * Creates worker that connects to an instance of the Temporal Service.
   *
   * @param service client to the Temporal Service endpoint.
   * @param domain domain that worker uses to poll.
   * @param taskList task list name worker uses to poll. It uses this name for both decision and
   *     activity task list polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @param stickyTaskListName
   */
  Worker(
      WorkflowServiceStubs service,
      String domain,
      String taskList,
      WorkerOptions options,
      DeciderCache cache,
      String stickyTaskListName,
      Duration stickyDecisionScheduleToStartTimeout,
      ThreadPoolExecutor threadPoolExecutor,
      List<ContextPropagator> contextPropagators) {

    Objects.requireNonNull(service, "service should not be null");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(domain), "domain should not be an empty string");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskList), "taskList should not be an empty string");
    this.cache = cache;
    this.stickyTaskListName = stickyTaskListName;
    this.threadPoolExecutor = Objects.requireNonNull(threadPoolExecutor);

    this.taskList = taskList;
    this.options = MoreObjects.firstNonNull(options, WorkerOptions.newBuilder().build());

    SingleWorkerOptions activityOptions =
        toActivityOptions(this.options, domain, taskList, contextPropagators);
    activityWorker =
        this.options.isDisableActivityWorker()
            ? null
            : new SyncActivityWorker(service, domain, taskList, activityOptions);

    SingleWorkerOptions workflowOptions =
        toWorkflowOptions(this.options, domain, taskList, contextPropagators);
    SingleWorkerOptions localActivityOptions =
        toLocalActivityOptions(this.options, domain, taskList, contextPropagators);
    workflowWorker =
        this.options.isDisableWorkflowWorker()
            ? null
            : new SyncWorkflowWorker(
                service,
                domain,
                taskList,
                this.options.getInterceptorFactory(),
                workflowOptions,
                localActivityOptions,
                this.cache,
                this.stickyTaskListName,
                stickyDecisionScheduleToStartTimeout,
                this.threadPoolExecutor);
  }

  private static SingleWorkerOptions toActivityOptions(
      WorkerOptions options,
      String domain,
      String taskList,
      List<ContextPropagator> contextPropagators) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return SingleWorkerOptions.newBuilder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getActivityPollerOptions())
        .setReportCompletionRetryOptions(options.getReportActivityCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportActivityFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentActivityExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .setContextPropagators(contextPropagators)
        .build();
  }

  private static SingleWorkerOptions toWorkflowOptions(
      WorkerOptions options,
      String domain,
      String taskList,
      List<ContextPropagator> contextPropagators) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return SingleWorkerOptions.newBuilder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getWorkflowPollerOptions())
        .setReportCompletionRetryOptions(options.getReportWorkflowCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportWorkflowFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentWorkflowExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .setContextPropagators(contextPropagators)
        .build();
  }

  private static SingleWorkerOptions toLocalActivityOptions(
      WorkerOptions options,
      String domain,
      String taskList,
      List<ContextPropagator> contextPropagators) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return SingleWorkerOptions.newBuilder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getWorkflowPollerOptions())
        .setReportCompletionRetryOptions(options.getReportWorkflowCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportWorkflowFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentLocalActivityExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .setContextPropagators(contextPropagators)
        .build();
  }

  SyncWorkflowWorker getWorkflowWorker() {
    return workflowWorker;
  }

  SyncActivityWorker getActivityWorker() {
    return activityWorker;
  }

  /**
   * Register workflow implementation classes with a worker. Overwrites previously registered types.
   * A workflow implementation class must implement at least one interface with a method annotated
   * with {@link WorkflowMethod}. That method becomes a workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>The reason for registration accepting workflow class, but not the workflow instance is that
   * workflows are stateful and a new instance is created for each workflow execution.
   */
  public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
    Preconditions.checkState(
        workflowWorker != null,
        "registerWorkflowImplementationTypes is not allowed when disableWorkflowWorker is set in worker options");
    Preconditions.checkState(
        !started.get(),
        "registerWorkflowImplementationTypes is not allowed after worker has started");

    workflowWorker.setWorkflowImplementationTypes(
        new WorkflowImplementationOptions.Builder().build(), workflowImplementationClasses);
  }

  /**
   * Register workflow implementation classes with a worker. Overwrites previously registered types.
   * A workflow implementation class must implement at least one interface with a method annotated
   * with {@link WorkflowMethod}. That method becomes a workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>The reason for registration accepting workflow class, but not the workflow instance is that
   * workflows are stateful and a new instance is created for each workflow execution.
   */
  public void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses) {
    Preconditions.checkState(
        workflowWorker != null,
        "registerWorkflowImplementationTypes is not allowed when disableWorkflowWorker is set in worker options");
    Preconditions.checkState(
        !started.get(),
        "registerWorkflowImplementationTypes is not allowed after worker has started");

    workflowWorker.setWorkflowImplementationTypes(options, workflowImplementationClasses);
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
   * Register activity implementation objects with a worker. Overwrites previously registered
   * objects. As activities are reentrant and stateless only one instance per activity type is
   * registered.
   *
   * <p>Implementations that share a worker must implement different interfaces as an activity type
   * is identified by the activity interface, not by the implementation.
   *
   * <p>
   */
  public void registerActivitiesImplementations(Object... activityImplementations) {
    Preconditions.checkState(
        !started.get(),
        "registerActivitiesImplementations is not allowed after worker has started");

    if (activityWorker != null) {
      activityWorker.setActivitiesImplementation(activityImplementations);
    }

    if (workflowWorker != null) {
      workflowWorker.setLocalActivitiesImplementation(activityImplementations);
    }
  }

  void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    if (workflowWorker != null) {
      workflowWorker.start();
    }
    if (activityWorker != null) {
      activityWorker.start();
    }
  }

  void shutdown() {
    if (activityWorker != null) {
      activityWorker.shutdown();
    }
    if (workflowWorker != null) {
      workflowWorker.shutdown();
    }
  }

  void shutdownNow() {
    if (activityWorker != null) {
      activityWorker.shutdownNow();
    }
    if (workflowWorker != null) {
      workflowWorker.shutdownNow();
    }
  }

  boolean isTerminated() {
    return activityWorker.isTerminated() && workflowWorker.isTerminated();
  }

  void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = InternalUtils.awaitTermination(activityWorker, unit.toMillis(timeout));
    InternalUtils.awaitTermination(workflowWorker, timeoutMillis);
  }

  @Override
  public String toString() {
    return "Worker{" + "options=" + options + '}';
  }

  /**
   * This is an utility method to replay a workflow execution using this particular instance of a
   * worker. This method is useful to troubleshoot workflows by running them in a debugger. To work
   * the workflow implementation type must be registered with this worker. There is no need to call
   * {@link #start()} to be able to call this method.
   *
   * @param history workflow execution history to replay
   * @throws Exception if replay failed for any reason
   */
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

  public String getTaskList() {
    return taskList;
  }

  @Override
  public void suspendPolling() {
    if (workflowWorker != null) {
      workflowWorker.suspendPolling();
    }

    if (activityWorker != null) {
      activityWorker.suspendPolling();
    }
  }

  @Override
  public void resumePolling() {
    if (workflowWorker != null) {
      workflowWorker.resumePolling();
    }

    if (activityWorker != null) {
      activityWorker.resumePolling();
    }
  }

  @Override
  public boolean isSuspended() {
    boolean workflowWorkerSuspended = true;
    if (workflowWorker != null) {
      workflowWorkerSuspended = workflowWorker.isSuspended();
    }

    boolean activityWorkerSuspended = activityWorker.isSuspended();
    if (activityWorker != null) {
      activityWorker.resumePolling();
    }

    return workflowWorkerSuspended && activityWorkerSuspended;
  }
}
