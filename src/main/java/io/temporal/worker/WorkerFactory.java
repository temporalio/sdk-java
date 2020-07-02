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
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.metrics.MetricsTag;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.worker.PollDecisionTaskDispatcher;
import io.temporal.internal.worker.Poller;
import io.temporal.internal.worker.PollerOptions;
import io.temporal.internal.worker.WorkflowPollTaskFactory;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains worker creation and lifecycle. */
public final class WorkerFactory {

  public static WorkerFactory newInstance(WorkflowClient workflowClient) {
    return WorkerFactory.newInstance(workflowClient, WorkerFactoryOptions.getDefaultInstance());
  }

  public static WorkerFactory newInstance(
      WorkflowClient workflowClient, WorkerFactoryOptions options) {
    return new WorkerFactory(workflowClient, options);
  }

  private static final String POLL_THREAD_NAME = "Host Local Workflow Poller";

  private final List<Worker> workers = new ArrayList<>();
  private final WorkflowClient workflowClient;
  private final UUID id =
      UUID.randomUUID(); // Guarantee uniqueness for stickyTaskQueueName when multiple factories
  private final ThreadPoolExecutor workflowThreadPool;
  private final AtomicInteger workflowThreadCounter = new AtomicInteger();
  private final WorkerFactoryOptions factoryOptions;

  private Poller<PollForDecisionTaskResponse> stickyPoller;
  private PollDecisionTaskDispatcher dispatcher;
  private DeciderCache cache;

  private State state = State.Initial;

  private final String statusErrorMessage =
      "attempted to %s while in %s state. Acceptable States: %s";
  private static final Logger log = LoggerFactory.getLogger(WorkerFactory.class);

  /**
   * Creates a factory. Workers will be connect to the temporal-server using the workflowService
   * client passed in.
   *
   * @param workflowClient client to the Temporal Service endpoint.
   * @param factoryOptions Options used to configure factory settings
   */
  private WorkerFactory(WorkflowClient workflowClient, WorkerFactoryOptions factoryOptions) {
    this.workflowClient = Objects.requireNonNull(workflowClient);
    this.factoryOptions =
        WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();

    workflowThreadPool =
        new ThreadPoolExecutor(
            0,
            this.factoryOptions.getMaxWorkflowThreadCount(),
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    workflowThreadPool.setThreadFactory(
        r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));

    Scope metricsScope =
        this.workflowClient
            .getWorkflowServiceStubs()
            .getOptions()
            .getMetricsScope()
            .tagged(
                new ImmutableMap.Builder<String, String>(2)
                    .put(MetricsTag.NAMESPACE, workflowClient.getOptions().getNamespace())
                    .put(MetricsTag.TASK_QUEUE, workflowClient.getOptions().getIdentity())
                    .build());

    this.cache = new DeciderCache(this.factoryOptions.getWorkflowCacheSize(), metricsScope);

    dispatcher = new PollDecisionTaskDispatcher(workflowClient.getWorkflowServiceStubs());
    stickyPoller =
        new Poller<>(
            id.toString(),
            new WorkflowPollTaskFactory(
                    workflowClient.getWorkflowServiceStubs(),
                    workflowClient.getOptions().getNamespace(),
                    getStickyTaskQueueName(),
                    metricsScope,
                    id.toString())
                .get(),
            dispatcher,
            PollerOptions.newBuilder()
                .setPollThreadNamePrefix(POLL_THREAD_NAME)
                .setPollThreadCount(this.factoryOptions.getWorkflowHostLocalPollThreadCount())
                .build(),
            metricsScope);
  }

  /**
   * Creates worker that connects to an instance of the Temporal Service. It uses the namespace
   * configured at the Factory level. New workers cannot be created after the start() has been
   * called
   *
   * @param taskQueue task queue name worker uses to poll. It uses this name for both decision and
   *     activity task queue polls.
   * @return Worker
   */
  public Worker newWorker(String taskQueue) {
    return newWorker(taskQueue, null);
  }

  /**
   * Creates worker that connects to an instance of the Temporal Service. It uses the namespace
   * configured at the Factory level. New workers cannot be created after the start() has been
   * called
   *
   * @param taskQueue task queue name worker uses to poll. It uses this name for both decision and
   *     activity task queue polls.
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @return Worker
   */
  public synchronized Worker newWorker(String taskQueue, WorkerOptions options) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskQueue), "taskQueue should not be an empty string");
    Preconditions.checkState(
        state == State.Initial,
        String.format(statusErrorMessage, "create new worker", state.name(), State.Initial.name()));
    Worker worker =
        new Worker(
            workflowClient,
            taskQueue,
            factoryOptions,
            options,
            cache,
            getStickyTaskQueueName(),
            workflowThreadPool,
            workflowClient.getOptions().getContextPropagators());
    workers.add(worker);
    dispatcher.subscribe(taskQueue, worker.workflowWorker);
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
  public WorkflowClient getWorkflowClient() {
    return workflowClient;
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received decision and
   * activity tasks are executed. After the shutdown calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
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
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received decision and
   * activity tasks are attempted to be stopped. This implementation cancels tasks via
   * Thread.interrupt(), so any task that fails to respond to interrupts may never terminate. Also
   * after the shutdownNow calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}. Invocation has no additional effect if
   * already shut down. This method does not wait for previously received tasks to complete
   * execution. Use {@link #awaitTermination(long, TimeUnit)} to do that.
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

  private String getStickyTaskQueueName() {
    return String.format("%s:%s", workflowClient.getOptions().getIdentity(), id);
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
