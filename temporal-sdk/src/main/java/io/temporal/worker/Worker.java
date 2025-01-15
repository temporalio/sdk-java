/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.worker.*;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts activity, nexus and workflow implementations. Uses long poll to receive workflow, activity
 * and nexus tasks and processes them in a correspondent thread pool.
 */
public final class Worker {
  private static final Logger log = LoggerFactory.getLogger(Worker.class);
  private final WorkerOptions options;
  private final String taskQueue;
  final SyncWorkflowWorker workflowWorker;
  final SyncActivityWorker activityWorker;
  final SyncNexusWorker nexusWorker;
  private final AtomicBoolean started = new AtomicBoolean();

  /**
   * Creates worker that connects to an instance of the Temporal Service.
   *
   * @param client client to the Temporal Service endpoint.
   * @param taskQueue task queue name worker uses to poll. It uses this name for both workflow and
   *     activity task queue polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @param useStickyTaskQueue if sticky task queue should be used
   * @param workflowThreadExecutor workflow methods thread executor
   */
  Worker(
      WorkflowClient client,
      String taskQueue,
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      Scope metricsScope,
      @Nonnull WorkflowRunLockManager runLocks,
      @Nonnull WorkflowExecutorCache cache,
      boolean useStickyTaskQueue,
      WorkflowThreadExecutor workflowThreadExecutor,
      List<ContextPropagator> contextPropagators) {

    Objects.requireNonNull(client, "client should not be null");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskQueue), "taskQueue should not be an empty string");
    this.taskQueue = taskQueue;
    this.options = WorkerOptions.newBuilder(options).validateAndBuildWithDefaults();
    factoryOptions = WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();
    WorkflowClientOptions clientOptions = client.getOptions();
    String namespace = clientOptions.getNamespace();
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(1).put(MetricsTag.TASK_QUEUE, taskQueue).build();
    Scope taggedScope = metricsScope.tagged(tags);
    SingleWorkerOptions activityOptions =
        toActivityOptions(
            factoryOptions, this.options, clientOptions, contextPropagators, taggedScope);
    if (this.options.isLocalActivityWorkerOnly()) {
      activityWorker = null;
    } else {
      SlotSupplier<ActivitySlotInfo> activitySlotSupplier =
          this.options.getWorkerTuner() == null
              ? new FixedSizeSlotSupplier<>(this.options.getMaxConcurrentActivityExecutionSize())
              : this.options.getWorkerTuner().getActivityTaskSlotSupplier();
      attachMetricsToResourceController(taggedScope, activitySlotSupplier);

      activityWorker =
          new SyncActivityWorker(
              client,
              namespace,
              taskQueue,
              this.options.getMaxTaskQueueActivitiesPerSecond(),
              activityOptions,
              activitySlotSupplier);
    }

    EagerActivityDispatcher eagerActivityDispatcher =
        (activityWorker != null && !this.options.isEagerExecutionDisabled())
            ? activityWorker.getEagerActivityDispatcher()
            : new EagerActivityDispatcher.NoopEagerActivityDispatcher();

    SingleWorkerOptions nexusOptions =
        toNexusOptions(
            factoryOptions, this.options, clientOptions, contextPropagators, taggedScope);
    SlotSupplier<NexusSlotInfo> nexusSlotSupplier =
        this.options.getWorkerTuner() == null
            ? new FixedSizeSlotSupplier<>(this.options.getMaxConcurrentNexusExecutionSize())
            : this.options.getWorkerTuner().getNexusSlotSupplier();
    attachMetricsToResourceController(taggedScope, nexusSlotSupplier);

    nexusWorker =
        new SyncNexusWorker(client, namespace, taskQueue, nexusOptions, nexusSlotSupplier);

    SingleWorkerOptions singleWorkerOptions =
        toWorkflowWorkerOptions(
            factoryOptions,
            this.options,
            clientOptions,
            taskQueue,
            contextPropagators,
            taggedScope);
    SingleWorkerOptions localActivityOptions =
        toLocalActivityOptions(
            factoryOptions, this.options, clientOptions, contextPropagators, taggedScope);

    SlotSupplier<WorkflowSlotInfo> workflowSlotSupplier =
        this.options.getWorkerTuner() == null
            ? new FixedSizeSlotSupplier<>(this.options.getMaxConcurrentWorkflowTaskExecutionSize())
            : this.options.getWorkerTuner().getWorkflowTaskSlotSupplier();
    attachMetricsToResourceController(taggedScope, workflowSlotSupplier);
    SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
        this.options.getWorkerTuner() == null
            ? new FixedSizeSlotSupplier<>(this.options.getMaxConcurrentLocalActivityExecutionSize())
            : this.options.getWorkerTuner().getLocalActivitySlotSupplier();
    attachMetricsToResourceController(taggedScope, localActivitySlotSupplier);

    workflowWorker =
        new SyncWorkflowWorker(
            client,
            namespace,
            taskQueue,
            singleWorkerOptions,
            localActivityOptions,
            runLocks,
            cache,
            useStickyTaskQueue ? getStickyTaskQueueName(client.getOptions().getIdentity()) : null,
            workflowThreadExecutor,
            eagerActivityDispatcher,
            workflowSlotSupplier,
            localActivitySlotSupplier);
  }

  /**
   * Registers workflow implementation classes with a worker. Can be called multiple times to add
   * more types. A workflow implementation class must implement at least one interface with a method
   * annotated with {@link WorkflowMethod}. By default, the short name of the interface is used as a
   * workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>Use {@link io.temporal.workflow.DynamicWorkflow} implementation to implement many workflow
   * types dynamically. It can be useful for implementing DSL based workflows. Only a single type
   * that implements DynamicWorkflow can be registered per worker.
   *
   * @throws TypeAlreadyRegisteredException if one of the workflow types is already registered
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
   * annotated with {@link WorkflowMethod}. By default, the short name of the interface is used as a
   * workflow type that this worker supports.
   *
   * <p>Implementations that share a worker must implement different interfaces as a workflow type
   * is identified by the workflow interface, not by the implementation.
   *
   * <p>Use {@link io.temporal.workflow.DynamicWorkflow} implementation to implement many workflow
   * types dynamically. It can be useful for implementing DSL based workflows. Only a single type
   * that implements DynamicWorkflow can be registered per worker.
   *
   * @throws TypeAlreadyRegisteredException if one of the workflow types is already registered
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
   * @param <R> type of the workflow object to create
   * @throws TypeAlreadyRegisteredException if one of the workflow types is already registered
   * @deprecated use {@link #registerWorkflowImplementationFactory(Class, Func,
   *     WorkflowImplementationOptions)} instead
   */
  @VisibleForTesting
  @Deprecated
  public <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> workflowInterface, Func<R> factory) {
    registerWorkflowImplementationFactory(workflowInterface, factory, options);
  }

  /**
   * <font color="red"> This method may behave differently from your expectations! This method makes
   * any {@link Throwable} thrown from a Workflow code to fail the Workflow Execution.
   *
   * <p>By default, only throwing {@link TemporalFailure} or an exception of class explicitly
   * specified on {@link WorkflowImplementationOptions.Builder#setFailWorkflowExceptionTypes} fails
   * Workflow Execution. Other exceptions fail Workflow Task instead of the whole Workflow
   * Execution. It is designed so that an exception which is not expected by the user, doesn't fail
   * the Workflow Execution. Which allows the user to fix Workflow implementation and then continue
   * the execution from the point it got stuck.
   *
   * <p>This method is misaligned with other workflow implementation registration methods in this
   * aspect.
   *
   * <p></font>
   *
   * @deprecated Use {@link #registerWorkflowImplementationFactory(Class, Func,
   *     WorkflowImplementationOptions)} with {@code
   *     WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(Throwable.class).build()}
   *     as a 3rd parameter to preserve the unexpected behavior of this method. <br>
   *     Or use {@link #registerWorkflowImplementationFactory(Class, Func)} with an expected
   *     behavior - Workflow Execution is failed only when a {@link TemporalFailure} subtype is
   *     thrown.
   */
  @VisibleForTesting
  @Deprecated
  public <R> void addWorkflowImplementationFactory(Class<R> workflowInterface, Func<R> factory) {
    WorkflowImplementationOptions unitTestingOptions =
        WorkflowImplementationOptions.newBuilder()
            .setFailWorkflowExceptionTypes(Throwable.class)
            .build();
    registerWorkflowImplementationFactory(workflowInterface, factory, unitTestingOptions);
  }

  /**
   * Configures a factory to use when an instance of a workflow implementation is created.
   *
   * <p>The only valid use for this method is unit testing and mocking. <br>
   * An example of mocking a child workflow:
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
   * <h1><font color="red">Workflow instantiation and Dependency Injection</font></h1>
   *
   * <font color="red"> This method may look convenient to integrate with dependency injection
   * frameworks and inject into workflow instances. Please note that Dependency Injection into
   * Workflow instances is strongly discouraged. Dependency Injection into Workflow Instances is a
   * direct way to cause changes that are incompatible with the persisted histories and cause
   * NonDeterministicException.
   *
   * <p>To provide an external configuration to a workflow in a deterministic way, use a Local
   * Activity that returns configuration to the workflow. Dependency Injection into Activity
   * instances is allowed. This way, the configuration is persisted into the history and maintained
   * same during replay.
   *
   * <p></font>
   *
   * @param workflowInterface Workflow interface that this factory implements
   * @param factory should create a new instance of the workflow implementation object every time
   *     it's called
   * @param options custom workflow implementation options for a worker
   * @param <R> type of the workflow object to create
   * @throws TypeAlreadyRegisteredException if one of the workflow types is already registered
   */
  @VisibleForTesting
  public <R> void registerWorkflowImplementationFactory(
      Class<R> workflowInterface, Func<R> factory, WorkflowImplementationOptions options) {
    workflowWorker.registerWorkflowImplementationFactory(options, workflowInterface, factory);
  }

  /**
   * Configures a factory to use when an instance of a workflow implementation is created.
   *
   * <p>The only valid use for this method is unit testing and mocking. <br>
   * An example of mocking a child workflow:
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
   * <h1><font color="red">Workflow instantiation and Dependency Injection</font></h1>
   *
   * <font color="red"> This method may look convenient to integrate with dependency injection
   * frameworks and inject into workflow instances. Please note that Dependency Injection into
   * Workflow instances is strongly discouraged. Dependency Injection into Workflow Instances is a
   * direct way to cause changes that are incompatible with the persisted histories and cause
   * NonDeterministicException.
   *
   * <p>To provide an external configuration to a workflow in a deterministic way, use a Local
   * Activity that returns configuration to the workflow. Dependency Injection into Activity
   * instances is allowed. This way, the configuration is persisted into the history and maintained
   * same during replay. </font>
   *
   * @param workflowInterface Workflow interface that this factory implements
   * @param factory should create a new instance of the workflow implementation object every time
   *     it's called
   * @param <R> type of the workflow object to create
   * @throws TypeAlreadyRegisteredException if one of the workflow types is already registered
   */
  @VisibleForTesting
  public <R> void registerWorkflowImplementationFactory(
      Class<R> workflowInterface, Func<R> factory) {
    workflowWorker.registerWorkflowImplementationFactory(
        WorkflowImplementationOptions.getDefaultInstance(), workflowInterface, factory);
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
   *
   * @throws TypeAlreadyRegisteredException if one of the activity types is already registered
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

  /**
   * Register Nexus service implementation objects with a worker.
   *
   * <p>A Nexus service object must be annotated with {@link io.nexusrpc.handler.ServiceImpl}.
   *
   * @throws TypeAlreadyRegisteredException if one of the services is already registered
   */
  @Experimental
  public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {
    Preconditions.checkState(
        !started.get(),
        "registerNexusServiceImplementation is not allowed after worker has started");
    nexusWorker.registerNexusServiceImplementation(nexusServiceImplementations);
  }

  void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    workflowWorker.start();
    nexusWorker.start();
    if (activityWorker != null) {
      activityWorker.start();
    }
  }

  CompletableFuture<Void> shutdown(ShutdownManager shutdownManager, boolean interruptUserTasks) {
    CompletableFuture<Void> workflowWorkerShutdownFuture =
        workflowWorker.shutdown(shutdownManager, interruptUserTasks);
    CompletableFuture<Void> nexusWorkerShutdownFuture =
        nexusWorker.shutdown(shutdownManager, interruptUserTasks);
    if (activityWorker != null) {
      return CompletableFuture.allOf(
          activityWorker.shutdown(shutdownManager, interruptUserTasks),
          workflowWorkerShutdownFuture,
          nexusWorkerShutdownFuture);
    } else {
      return CompletableFuture.allOf(workflowWorkerShutdownFuture, nexusWorkerShutdownFuture);
    }
  }

  boolean isTerminated() {
    boolean isTerminated = workflowWorker.isTerminated();
    isTerminated &= nexusWorker.isTerminated();
    if (activityWorker != null) {
      isTerminated &= activityWorker.isTerminated();
    }
    return isTerminated;
  }

  void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    if (activityWorker != null) {
      timeoutMillis = ShutdownManager.awaitTermination(activityWorker, timeoutMillis);
    }
    timeoutMillis = ShutdownManager.awaitTermination(nexusWorker, timeoutMillis);
    ShutdownManager.awaitTermination(workflowWorker, timeoutMillis);
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
  @SuppressWarnings("deprecation")
  public void replayWorkflowExecution(io.temporal.internal.common.WorkflowExecutionHistory history)
      throws Exception {
    workflowWorker.queryWorkflowExecution(
        history,
        WorkflowClient.QUERY_TYPE_REPLAY_ONLY,
        String.class,
        String.class,
        new Object[] {});
  }

  /**
   * This is a utility method to replay a workflow execution using this particular instance of a
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

  public void suspendPolling() {
    workflowWorker.suspendPolling();
    nexusWorker.suspendPolling();
    if (activityWorker != null) {
      activityWorker.suspendPolling();
    }
  }

  public void resumePolling() {
    workflowWorker.resumePolling();
    nexusWorker.resumePolling();
    if (activityWorker != null) {
      activityWorker.resumePolling();
    }
  }

  public boolean isSuspended() {
    return workflowWorker.isSuspended()
        && nexusWorker.isSuspended()
        && (activityWorker == null || activityWorker.isSuspended());
  }

  @Nullable
  public WorkflowTaskDispatchHandle reserveWorkflowExecutor() {
    return workflowWorker.reserveWorkflowExecutor();
  }

  private static String getStickyTaskQueueName(String workerIdentity) {
    // Unique id is needed to avoid collisions with other workers that may be created for the same
    // task queue and with the same identity.
    // We don't include normal task queue name here because it's typically clear which task queue a
    // sticky queue is for from the workflow execution histories.
    UUID uniqueId = UUID.randomUUID();
    return String.format("%s:%s", workerIdentity, uniqueId);
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

  private static SingleWorkerOptions toActivityOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators,
      Scope metricsScope) {
    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setUsingVirtualThreads(options.isUsingVirtualThreadsOnActivityWorker())
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setMaximumPollRatePerSecond(options.getMaxWorkerActivitiesPerSecond())
                .setPollThreadCount(options.getMaxConcurrentActivityTaskPollers())
                .setUsingVirtualThreads(options.isUsingVirtualThreadsOnActivityWorker())
                .build())
        .setMetricsScope(metricsScope)
        .build();
  }

  private static SingleWorkerOptions toNexusOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators,
      Scope metricsScope) {
    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setPollThreadCount(options.getMaxConcurrentNexusTaskPollers())
                .setUsingVirtualThreads(options.isUsingVirtualThreadsOnNexusWorker())
                .build())
        .setMetricsScope(metricsScope)
        .setUsingVirtualThreads(options.isUsingVirtualThreadsOnNexusWorker())
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

    Duration stickyQueueScheduleToStartTimeout = options.getStickyQueueScheduleToStartTimeout();
    if (WorkerOptions.DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT.equals(
            stickyQueueScheduleToStartTimeout)
        && factoryOptions.getWorkflowHostLocalTaskQueueScheduleToStartTimeout() != null) {
      stickyQueueScheduleToStartTimeout =
          factoryOptions.getWorkflowHostLocalTaskQueueScheduleToStartTimeout();
    }

    int maxConcurrentWorkflowTaskPollers = options.getMaxConcurrentWorkflowTaskPollers();
    if (maxConcurrentWorkflowTaskPollers == 1) {
      log.warn(
          "WorkerOptions.Builder#setMaxConcurrentWorkflowTaskPollers was set to 1. This is an illegal value. The number of Workflow Task Pollers is forced to 2. See documentation on WorkerOptions.Builder#setMaxConcurrentWorkflowTaskPollers");
      maxConcurrentWorkflowTaskPollers = 2;
    }

    return toSingleWorkerOptions(factoryOptions, options, clientOptions, contextPropagators)
        .setPollerOptions(
            PollerOptions.newBuilder()
                .setPollThreadCount(maxConcurrentWorkflowTaskPollers)
                .setUsingVirtualThreads(options.isUsingVirtualThreadsOnWorkflowWorker())
                .build())
        .setStickyQueueScheduleToStartTimeout(stickyQueueScheduleToStartTimeout)
        .setStickyTaskQueueDrainTimeout(options.getStickyTaskQueueDrainTimeout())
        .setUsingVirtualThreads(options.isUsingVirtualThreadsOnWorkflowWorker())
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
        .setMetricsScope(metricsScope)
        .setUsingVirtualThreads(options.isUsingVirtualThreadsOnLocalActivityWorker())
        .build();
  }

  @SuppressWarnings("deprecation")
  private static SingleWorkerOptions.Builder toSingleWorkerOptions(
      WorkerFactoryOptions factoryOptions,
      WorkerOptions options,
      WorkflowClientOptions clientOptions,
      List<ContextPropagator> contextPropagators) {
    String buildId = null;
    if (options.getBuildId() != null) {
      buildId = options.getBuildId();
    } else if (clientOptions.getBinaryChecksum() != null) {
      buildId = clientOptions.getBinaryChecksum();
    }

    String identity = clientOptions.getIdentity();
    if (options.getIdentity() != null) {
      identity = options.getIdentity();
    }

    return SingleWorkerOptions.newBuilder()
        .setDataConverter(clientOptions.getDataConverter())
        .setIdentity(identity)
        .setBuildId(buildId)
        .setUseBuildIdForVersioning(options.isUsingBuildIdForVersioning())
        .setEnableLoggingInReplay(factoryOptions.isEnableLoggingInReplay())
        .setContextPropagators(contextPropagators)
        .setWorkerInterceptors(factoryOptions.getWorkerInterceptors())
        .setMaxHeartbeatThrottleInterval(options.getMaxHeartbeatThrottleInterval())
        .setDefaultHeartbeatThrottleInterval(options.getDefaultHeartbeatThrottleInterval());
  }

  /**
   * If any slot supplier is resource-based, we want to attach a metrics scope to the controller
   * (before it's labelled with the worker type).
   */
  private static void attachMetricsToResourceController(
      Scope metricsScope, SlotSupplier<?> supplier) {
    if (supplier instanceof ResourceBasedSlotSupplier) {
      ((ResourceBasedSlotSupplier<?>) supplier)
          .getResourceController()
          .setMetricsScope(metricsScope);
    }
  }
}
