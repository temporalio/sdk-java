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

package com.uber.cadence.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.metrics.MetricsTag;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.internal.replay.DeciderCache;
import com.uber.cadence.internal.sync.SyncActivityWorker;
import com.uber.cadence.internal.sync.SyncWorkflowWorker;
import com.uber.cadence.internal.worker.PollDecisionTaskDispatcher;
import com.uber.cadence.internal.worker.Poller;
import com.uber.cadence.internal.worker.PollerOptions;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.internal.worker.Suspendable;
import com.uber.cadence.internal.worker.WorkflowPollTaskFactory;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.WorkflowMethod;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
   * Creates worker that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   * @param taskList task list name worker uses to poll. It uses this name for both decision and
   *     activity task list polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @param stickyTaskListName
   */
  private Worker(
      IWorkflowService service,
      String domain,
      String taskList,
      WorkerOptions options,
      DeciderCache cache,
      String stickyTaskListName,
      Duration stickyDecisionScheduleToStartTimeout,
      ThreadPoolExecutor threadPoolExecutor) {

    Objects.requireNonNull(service, "service should not be null");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(domain), "domain should not be an empty string");
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskList), "taskList should not be an empty string");
    this.cache = cache;
    this.stickyTaskListName = stickyTaskListName;
    this.threadPoolExecutor = Objects.requireNonNull(threadPoolExecutor);

    this.taskList = taskList;
    this.options = MoreObjects.firstNonNull(options, new WorkerOptions.Builder().build());

    SingleWorkerOptions activityOptions = toActivityOptions(this.options, domain, taskList);
    activityWorker =
        this.options.isDisableActivityWorker()
            ? null
            : new SyncActivityWorker(service, domain, taskList, activityOptions);

    SingleWorkerOptions workflowOptions = toWorkflowOptions(this.options, domain, taskList);
    SingleWorkerOptions localActivityOptions =
        toLocalActivityOptions(this.options, domain, taskList);
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
      WorkerOptions options, String domain, String taskList) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return new SingleWorkerOptions.Builder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getActivityPollerOptions())
        .setReportCompletionRetryOptions(options.getReportActivityCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportActivityFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentActivityExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .build();
  }

  private static SingleWorkerOptions toWorkflowOptions(
      WorkerOptions options, String domain, String taskList) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return new SingleWorkerOptions.Builder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getWorkflowPollerOptions())
        .setReportCompletionRetryOptions(options.getReportWorkflowCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportWorkflowFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentWorkflowExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .build();
  }

  private static SingleWorkerOptions toLocalActivityOptions(
      WorkerOptions options, String domain, String taskList) {
    Map<String, String> tags =
        new ImmutableMap.Builder<String, String>(2)
            .put(MetricsTag.DOMAIN, domain)
            .put(MetricsTag.TASK_LIST, taskList)
            .build();
    return new SingleWorkerOptions.Builder()
        .setDataConverter(options.getDataConverter())
        .setIdentity(options.getIdentity())
        .setPollerOptions(options.getWorkflowPollerOptions())
        .setReportCompletionRetryOptions(options.getReportWorkflowCompletionRetryOptions())
        .setReportFailureRetryOptions(options.getReportWorkflowFailureRetryOptions())
        .setTaskExecutorThreadPoolSize(options.getMaxConcurrentLocalActivityExecutionSize())
        .setMetricsScope(options.getMetricsScope().tagged(tags))
        .setEnableLoggingInReplay(options.getEnableLoggingInReplay())
        .build();
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
   *   worker.addWorkflowImplementationFactory(ChildWorkflow.class, () -> {
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

  private void start() {
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

  private void shutdown() {
    if (activityWorker != null) {
      activityWorker.shutdown();
    }
    if (workflowWorker != null) {
      workflowWorker.shutdown();
    }
  }

  private void shutdownNow() {
    if (activityWorker != null) {
      activityWorker.shutdownNow();
    }
    if (workflowWorker != null) {
      workflowWorker.shutdownNow();
    }
  }

  private boolean isTerminated() {
    return activityWorker.isTerminated() && workflowWorker.isTerminated();
  }

  private void awaitTermination(long timeout, TimeUnit unit) {
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

  /** Maintains worker creation and lifecycle. */
  public static final class Factory {
    private final List<Worker> workers = new ArrayList<>();
    private final IWorkflowService workflowService;
    /** Indicates if factory owns the service. An owned service is closed on shutdown. */
    private final boolean closeServiceOnShutdown;

    private final String domain;
    private final UUID id =
        UUID.randomUUID(); // Guarantee uniqueness for stickyTaskListName when multiple factories
    private final ThreadPoolExecutor workflowThreadPool;
    private final AtomicInteger workflowThreadCounter = new AtomicInteger();
    private final FactoryOptions factoryOptions;

    private Poller<PollForDecisionTaskResponse> stickyPoller;
    private PollDecisionTaskDispatcher dispatcher;
    private DeciderCache cache;

    private State state = State.Initial;

    private final String statusErrorMessage =
        "attempted to %s while in %s state. Acceptable States: %s";
    private static final Logger log = LoggerFactory.getLogger(Factory.class);
    /**
     * Creates a factory. Workers will be connected to a local deployment of cadence-server
     *
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(String domain) {
      this(new WorkflowServiceTChannel(), true, domain, null);
    }

    /**
     * Creates a factory. Workers will be connected to the cadence-server at the specific host and
     * port.
     *
     * @param host host used by the underlying workflowServiceClient to connect to.
     * @param port port used by the underlying workflowServiceClient to connect to.
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(String host, int port, String domain) {
      this(new WorkflowServiceTChannel(host, port), true, domain, null);
    }

    /**
     * Creates a factory connected to a local deployment of cadence-server.
     *
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(String domain, FactoryOptions factoryOptions) {
      this(new WorkflowServiceTChannel(), true, domain, factoryOptions);
    }

    /**
     * Creates a factory. Workers will be connected to the cadence-server at the specific host and
     * port.
     *
     * @param host host used by the underlying workflowServiceClient to connect to.
     * @param port port used by the underlying workflowServiceClient to connect to.
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(String host, int port, String domain, FactoryOptions factoryOptions) {
      this(new WorkflowServiceTChannel(host, port), true, domain, factoryOptions);
    }

    /**
     * Creates a factory. Workers will be connect to the cadence-server using the workflowService
     * client passed in.
     *
     * @param workflowService client to the Cadence Service endpoint.
     * @param domain Domain used by workers to poll for workflows.
     */
    public Factory(IWorkflowService workflowService, String domain) {
      this(workflowService, false, domain, null);
    }

    /**
     * Creates a factory. Workers will be connect to the cadence-server using the workflowService
     * client passed in.
     *
     * @param workflowService client to the Cadence Service endpoint.
     * @param domain Domain used by workers to poll for workflows.
     * @param factoryOptions Options used to configure factory settings
     */
    public Factory(IWorkflowService workflowService, String domain, FactoryOptions factoryOptions) {
      this(workflowService, false, domain, factoryOptions);
    }

    private Factory(
        IWorkflowService workflowService,
        boolean closeServiceOnShutdown,
        String domain,
        FactoryOptions factoryOptions) {
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(domain), "domain should not be an empty string");

      this.domain = domain;
      this.workflowService =
          Objects.requireNonNull(workflowService, "workflowService should not be null");
      this.closeServiceOnShutdown = closeServiceOnShutdown;
      factoryOptions =
          factoryOptions == null ? new FactoryOptions.Builder().build() : factoryOptions;
      this.factoryOptions = factoryOptions;

      workflowThreadPool =
          new ThreadPoolExecutor(
              0,
              this.factoryOptions.maxWorkflowThreadCount,
              1,
              TimeUnit.SECONDS,
              new SynchronousQueue<>());
      workflowThreadPool.setThreadFactory(
          r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));

      if (this.factoryOptions.disableStickyExecution) {
        return;
      }

      Scope metricsScope =
          this.factoryOptions.metricsScope.tagged(
              new ImmutableMap.Builder<String, String>(2)
                  .put(MetricsTag.DOMAIN, domain)
                  .put(MetricsTag.TASK_LIST, getHostName())
                  .build());

      this.cache = new DeciderCache(this.factoryOptions.cacheMaximumSize, metricsScope);

      dispatcher = new PollDecisionTaskDispatcher(workflowService);
      stickyPoller =
          new Poller<>(
              id.toString(),
              new WorkflowPollTaskFactory(
                      workflowService, domain, getStickyTaskListName(), metricsScope, id.toString())
                  .get(),
              dispatcher,
              this.factoryOptions.stickyWorkflowPollerOptions,
              metricsScope);
    }

    /**
     * Creates worker that connects to an instance of the Cadence Service. It uses the domain
     * configured at the Factory level. New workers cannot be created after the start() has been
     * called
     *
     * @param taskList task list name worker uses to poll. It uses this name for both decision and
     *     activity task list polls.
     * @return Worker
     */
    public Worker newWorker(String taskList) {
      return newWorker(taskList, null);
    }

    /**
     * Creates worker that connects to an instance of the Cadence Service. It uses the domain
     * configured at the Factory level. New workers cannot be created after the start() has been
     * called
     *
     * @param taskList task list name worker uses to poll. It uses this name for both decision and
     *     activity task list polls.
     * @param options Options (like {@link DataConverter} override) for configuring worker.
     * @return Worker
     */
    public synchronized Worker newWorker(String taskList, WorkerOptions options) {
      Preconditions.checkArgument(
          !Strings.isNullOrEmpty(taskList), "taskList should not be an empty string");
      Preconditions.checkState(
          state == State.Initial,
          String.format(
              statusErrorMessage, "create new worker", state.name(), State.Initial.name()));
      Worker worker =
          new Worker(
              workflowService,
              domain,
              taskList,
              options,
              cache,
              getStickyTaskListName(),
              Duration.ofSeconds(factoryOptions.stickyDecisionScheduleToStartTimeoutInSeconds),
              workflowThreadPool);
      workers.add(worker);

      if (!this.factoryOptions.disableStickyExecution) {
        dispatcher.subscribe(taskList, worker.workflowWorker);
      }
      return worker;
    }

    /** Starts all the workers created by this factory. */
    public synchronized void start() {
      Preconditions.checkState(
          state == State.Initial || state == State.Started,
          String.format(
              statusErrorMessage,
              "start WorkerFactory",
              state.name(),
              String.format("%s, %s", State.Initial.name(), State.Initial.name())));
      if (state == State.Started) {
        return;
      }
      state = State.Started;

      for (Worker worker : workers) {
        worker.start();
      }

      if (stickyPoller != null) {
        stickyPoller.start();
      }
    }

    /** Was {@link #start()} called. */
    public synchronized boolean isStarted() {
      return state != State.Initial;
    }

    /** Was {@link #shutdown()} or {@link #shutdownNow()} called. */
    public synchronized boolean isShutdown() {
      return state == State.Shutdown;
    }

    /**
     * Returns true if all tasks have completed following shut down. Note that isTerminated is never
     * true unless either shutdown or shutdownNow was called first.
     */
    public synchronized boolean isTerminated() {
      if (state != State.Shutdown) {
        return false;
      }
      if (stickyPoller != null) {
        if (!stickyPoller.isTerminated()) {
          return false;
        }
      }
      for (Worker worker : workers) {
        if (!worker.isTerminated()) {
          return false;
        }
      }
      return true;
    }

    /** @return instance of the cadence client that this worker uses. */
    public IWorkflowService getWorkflowService() {
      return workflowService;
    }

    /**
     * Initiates an orderly shutdown in which polls are stopped and already received decision and
     * activity tasks are executed. After the shutdown calls to {@link
     * com.uber.cadence.activity.Activity#heartbeat(Object)} start throwing {@link
     * com.uber.cadence.client.ActivityWorkerShutdownException}. Invocation has no additional effect
     * if already shut down. This method does not wait for previously received tasks to complete
     * execution. Use {@link #awaitTermination(long, TimeUnit)} to do that.
     */
    public synchronized void shutdown() {
      log.info("shutdown");
      state = State.Shutdown;
      if (stickyPoller != null) {
        stickyPoller.shutdown();
        // To ensure that it doesn't get new tasks before workers are shutdown.
        stickyPoller.awaitTermination(1, TimeUnit.SECONDS);
      }
      for (Worker worker : workers) {
        worker.shutdown();
      }
      closeServiceWhenTerminated();
    }

    /**
     * Closes Cadence client object. It should be closed only after all tasks have completed
     * execution as tasks use it to report completion.
     */
    private void closeServiceWhenTerminated() {
      if (closeServiceOnShutdown) {
        ForkJoinPool.commonPool()
            .execute(
                () -> {
                  // Service is used to report task completions.
                  awaitTermination(1, TimeUnit.HOURS);
                  log.info("Closing workflow service client");
                  workflowService.close();
                });
      }
    }

    /**
     * Initiates an orderly shutdown in which polls are stopped and already received decision and
     * activity tasks are attempted to be stopped. This implementation cancels tasks via
     * Thread.interrupt(), so any task that fails to respond to interrupts may never terminate. Also
     * after the shutdownNow calls to {@link com.uber.cadence.activity.Activity#heartbeat(Object)}
     * start throwing {@link com.uber.cadence.client.ActivityWorkerShutdownException}. Invocation
     * has no additional effect if already shut down. This method does not wait for previously
     * received tasks to complete execution. Use {@link #awaitTermination(long, TimeUnit)} to do
     * that.
     */
    public synchronized void shutdownNow() {
      log.info("shutdownNow");
      state = State.Shutdown;
      if (stickyPoller != null) {
        stickyPoller.shutdownNow();
        // To ensure that it doesn't get new tasks before workers are shutdown.
        stickyPoller.awaitTermination(1, TimeUnit.SECONDS);
      }
      for (Worker worker : workers) {
        worker.shutdownNow();
      }
      closeServiceWhenTerminated();
    }

    /**
     * Blocks until all tasks have completed execution after a shutdown request, or the timeout
     * occurs, or the current thread is interrupted, whichever happens first.
     */
    public void awaitTermination(long timeout, TimeUnit unit) {
      log.info("awaitTermination begin");
      long timeoutMillis = unit.toMillis(timeout);
      timeoutMillis = InternalUtils.awaitTermination(stickyPoller, timeoutMillis);
      for (Worker worker : workers) {
        long t = timeoutMillis; // closure needs immutable value
        timeoutMillis =
            InternalUtils.awaitTermination(
                timeoutMillis, () -> worker.awaitTermination(t, TimeUnit.MILLISECONDS));
      }
      log.info("awaitTermination done");
    }

    @VisibleForTesting
    DeciderCache getCache() {
      return this.cache;
    }

    @VisibleForTesting
    String getHostName() {
      try {
        return InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        return "UnknownHost";
      }
    }

    private String getStickyTaskListName() {
      return this.factoryOptions.disableStickyExecution
          ? null
          : String.format("%s:%s", getHostName(), id);
    }

    public synchronized void suspendPolling() {
      if (state != State.Started) {
        return;
      }

      log.info("suspendPolling");
      state = State.Suspended;
      if (stickyPoller != null) {
        stickyPoller.suspendPolling();
      }
      for (Worker worker : workers) {
        worker.suspendPolling();
      }
    }

    public synchronized void resumePolling() {
      if (state != State.Suspended) {
        return;
      }

      log.info("resumePolling");
      state = State.Started;
      if (stickyPoller != null) {
        stickyPoller.resumePolling();
      }
      for (Worker worker : workers) {
        worker.resumePolling();
      }
    }

    enum State {
      Initial,
      Started,
      Suspended,
      Shutdown
    }
  }

  public static class FactoryOptions {
    public static class Builder {
      private boolean disableStickyExecution;
      private int stickyDecisionScheduleToStartTimeoutInSeconds = 5;
      private int cacheMaximumSize = 600;
      private int maxWorkflowThreadCount = 600;
      private PollerOptions stickyWorkflowPollerOptions;
      private Scope metricScope;

      /**
       * When set to false it will create an affinity between the worker and the workflow run it's
       * processing. Workers will cache workflows and will handle all decisions for that workflow
       * instance until it's complete or evicted from the cache. Default value is false.
       */
      public Builder setDisableStickyExecution(boolean disableStickyExecution) {
        this.disableStickyExecution = disableStickyExecution;
        return this;
      }

      /**
       * When Sticky execution is enabled this will set the maximum allowed number of workflows
       * cached. This cache is shared by all workers created by the Factory. Default value is 600
       */
      public Builder setCacheMaximumSize(int cacheMaximumSize) {
        this.cacheMaximumSize = cacheMaximumSize;
        return this;
      }

      /**
       * Maximum number of threads available for workflow execution across all workers created by
       * the Factory.
       */
      public Builder setMaxWorkflowThreadCount(int maxWorkflowThreadCount) {
        this.maxWorkflowThreadCount = maxWorkflowThreadCount;
        return this;
      }

      /**
       * Timeout for sticky workflow decision to be picked up by the host assigned to it. Once it
       * times out then it can be picked up by any worker. Default value is 5 seconds.
       */
      public Builder setStickyDecisionScheduleToStartTimeoutInSeconds(
          int stickyDecisionScheduleToStartTimeoutInSeconds) {
        this.stickyDecisionScheduleToStartTimeoutInSeconds =
            stickyDecisionScheduleToStartTimeoutInSeconds;
        return this;
      }

      /**
       * PollerOptions for poller responsible for polling for decisions for workflows cached by all
       * workers created by this factory.
       */
      public Builder setStickyWorkflowPollerOptions(PollerOptions stickyWorkflowPollerOptions) {
        this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
        return this;
      }

      public Builder setMetricScope(Scope metricScope) {
        this.metricScope = metricScope;
        return this;
      }

      public FactoryOptions build() {
        return new FactoryOptions(
            disableStickyExecution,
            cacheMaximumSize,
            maxWorkflowThreadCount,
            stickyDecisionScheduleToStartTimeoutInSeconds,
            stickyWorkflowPollerOptions,
            metricScope);
      }
    }

    private final boolean disableStickyExecution;
    private final int cacheMaximumSize;
    private final int maxWorkflowThreadCount;
    private final int stickyDecisionScheduleToStartTimeoutInSeconds;
    private final PollerOptions stickyWorkflowPollerOptions;
    private final Scope metricsScope;

    private FactoryOptions(
        boolean disableStickyExecution,
        int cacheMaximumSize,
        int maxWorkflowThreadCount,
        int stickyDecisionScheduleToStartTimeoutInSeconds,
        PollerOptions stickyWorkflowPollerOptions,
        Scope metricsScope) {
      Preconditions.checkArgument(
          cacheMaximumSize > 0, "cacheMaximumSize should be greater than 0");
      Preconditions.checkArgument(
          maxWorkflowThreadCount > 0, "maxWorkflowThreadCount should be greater than 0");
      Preconditions.checkArgument(
          stickyDecisionScheduleToStartTimeoutInSeconds > 0,
          "stickyDecisionScheduleToStartTimeoutInSeconds should be greater than 0");

      this.disableStickyExecution = disableStickyExecution;
      this.cacheMaximumSize = cacheMaximumSize;
      this.maxWorkflowThreadCount = maxWorkflowThreadCount;
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          stickyDecisionScheduleToStartTimeoutInSeconds;

      if (stickyWorkflowPollerOptions == null) {
        this.stickyWorkflowPollerOptions =
            new PollerOptions.Builder()
                .setPollBackoffInitialInterval(Duration.ofMillis(200))
                .setPollBackoffMaximumInterval(Duration.ofSeconds(20))
                .setPollThreadCount(1)
                .build();
      } else {
        this.stickyWorkflowPollerOptions = stickyWorkflowPollerOptions;
      }

      if (metricsScope == null) {
        this.metricsScope = NoopScope.getInstance();
      } else {
        this.metricsScope = metricsScope;
      }
    }
  }
}
