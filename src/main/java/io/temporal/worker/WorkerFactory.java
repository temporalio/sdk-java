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
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.converter.DataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.worker.PollDecisionTaskDispatcher;
import io.temporal.internal.worker.Poller;
import io.temporal.internal.worker.WorkflowPollTaskFactory;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains worker creation and lifecycle. */
public final class WorkerFactory {
  private final List<Worker> workers = new ArrayList<>();
  private final WorkflowServiceStubs workflowService;
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
  private static final Logger log = LoggerFactory.getLogger(WorkerFactory.class);

  /**
   * Creates a factory. Workers will be connected to a local deployment of temporal-server
   *
   * @param domain Domain used by workers to poll for workflows.
   */
  public WorkerFactory(String domain) {
    this(WorkflowServiceStubs.newInstance(), true, domain, null);
  }

  /**
   * Creates a factory. Workers will be connected to the temporal-server at the specific host and
   * port.
   *
   * @param target address of the Temporal service
   * @param domain Domain used by workers to poll for workflows.
   */
  public WorkerFactory(String target, String domain) {
    this(WorkflowServiceStubs.newInstance(target), true, domain, null);
  }

  /**
   * Creates a factory connected to a local deployment of temporal-server.
   *
   * @param domain Domain used by workers to poll for workflows.
   * @param factoryOptions Options used to configure factory settings
   */
  public WorkerFactory(String domain, FactoryOptions factoryOptions) {
    this(WorkflowServiceStubs.newInstance(), true, domain, factoryOptions);
  }

  /**
   * Creates a factory. Workers will be connected to the temporal-server at the specific host and
   * port.
   *
   * @param target address of temporal service
   * @param domain Domain used by workers to poll for workflows.
   * @param factoryOptions Options used to configure factory settings
   */
  public WorkerFactory(String target, String domain, FactoryOptions factoryOptions) {
    this(WorkflowServiceStubs.newInstance(target), true, domain, factoryOptions);
  }

  /**
   * Creates a factory. Workers will be connect to the temporal-server using the workflowService
   * client passed in.
   *
   * @param workflowService client to the Temporal Service endpoint.
   * @param domain Domain used by workers to poll for workflows.
   */
  public WorkerFactory(WorkflowServiceStubs workflowService, String domain) {
    this(workflowService, false, domain, null);
  }

  /**
   * Creates a factory. Workers will be connect to the temporal-server using the workflowService
   * client passed in.
   *
   * @param workflowService client to the Temporal Service endpoint.
   * @param domain Domain used by workers to poll for workflows.
   * @param factoryOptions Options used to configure factory settings
   */
  public WorkerFactory(
      WorkflowServiceStubs workflowService, String domain, FactoryOptions factoryOptions) {
    this(workflowService, false, domain, factoryOptions);
  }

  private WorkerFactory(
      WorkflowServiceStubs workflowService,
      boolean closeServiceOnShutdown,
      String domain,
      FactoryOptions factoryOptions) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(domain), "domain should not be an empty string");

    this.domain = domain;
    this.workflowService =
        Objects.requireNonNull(workflowService, "workflowService should not be null");
    this.closeServiceOnShutdown = closeServiceOnShutdown;
    factoryOptions = FactoryOptions.newBuilder(factoryOptions).build();
    this.factoryOptions = factoryOptions;

    workflowThreadPool =
        new ThreadPoolExecutor(
            0,
            this.factoryOptions.getMaxWorkflowThreadCount(),
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    workflowThreadPool.setThreadFactory(
        r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));

    if (this.factoryOptions.isDisableStickyExecution()) {
      return;
    }

    Scope metricsScope =
        this.factoryOptions
            .getMetricsScope()
            .tagged(
                new ImmutableMap.Builder<String, String>(2)
                    .put(MetricsTag.DOMAIN, domain)
                    .put(MetricsTag.TASK_LIST, getHostName())
                    .build());

    this.cache = new DeciderCache(this.factoryOptions.getCacheMaximumSize(), metricsScope);

    dispatcher = new PollDecisionTaskDispatcher(workflowService);
    stickyPoller =
        new Poller<>(
            id.toString(),
            new WorkflowPollTaskFactory(
                    workflowService, domain, getStickyTaskListName(), metricsScope, id.toString())
                .get(),
            dispatcher,
            this.factoryOptions.getStickyWorkflowPollerOptions(),
            metricsScope);
  }

  /**
   * Creates worker that connects to an instance of the Temporal Service. It uses the domain
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
   * Creates worker that connects to an instance of the Temporal Service. It uses the domain
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
        String.format(statusErrorMessage, "create new worker", state.name(), State.Initial.name()));
    Worker worker =
        new Worker(
            workflowService,
            domain,
            taskList,
            options,
            cache,
            getStickyTaskListName(),
            Duration.ofSeconds(factoryOptions.getStickyDecisionScheduleToStartTimeoutInSeconds()),
            workflowThreadPool,
            factoryOptions.getContextPropagators());
    workers.add(worker);

    if (!this.factoryOptions.isDisableStickyExecution()) {
      dispatcher.subscribe(taskList, worker.getWorkflowWorker());
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

  /** @return instance of the Temporal client that this worker uses. */
  public WorkflowServiceStubs getWorkflowService() {
    return workflowService;
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received decision and
   * activity tasks are executed. After the shutdown calls to {@link
   * io.temporal.activity.Activity#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}. Invocation has no additional effect if
   * already shut down. This method does not wait for previously received tasks to complete
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
   * Closes Temporal client object. It should be closed only after all tasks have completed
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
                // Shutdown service connection only after workers are down.
                // Otherwise tasks would not be able to report about their completion.
                workflowService.shutdownNow();
                try {
                  workflowService.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  e.printStackTrace();
                }
              });
    }
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received decision and
   * activity tasks are attempted to be stopped. This implementation cancels tasks via
   * Thread.interrupt(), so any task that fails to respond to interrupts may never terminate. Also
   * after the shutdownNow calls to {@link io.temporal.activity.Activity#heartbeat(Object)} start
   * throwing {@link io.temporal.client.ActivityWorkerShutdownException}. Invocation has no
   * additional effect if already shut down. This method does not wait for previously received tasks
   * to complete execution. Use {@link #awaitTermination(long, TimeUnit)} to do that.
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
    return this.factoryOptions.isDisableStickyExecution()
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
