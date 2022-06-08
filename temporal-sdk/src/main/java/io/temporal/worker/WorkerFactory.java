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
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.worker.*;
import io.temporal.serviceclient.MetricsTag;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains worker creation and lifecycle. */
public final class WorkerFactory {
  private static final Logger log = LoggerFactory.getLogger(WorkerFactory.class);

  private final Scope metricsScope;

  private final Map<String, Worker> workers = new HashMap<>();
  private final WorkflowClient workflowClient;
  private final UUID id =
      UUID.randomUUID(); // Guarantee uniqueness for stickyTaskQueueName when multiple factories
  private final ThreadPoolExecutor workflowThreadPool;
  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final AtomicInteger workflowThreadCounter = new AtomicInteger();
  private final WorkerFactoryOptions factoryOptions;

  private final StickyPoller stickyPoller;
  private final WorkflowExecutorCache cache;

  private State state = State.Initial;

  private final String statusErrorMessage =
      "attempted to %s while in %s state. Acceptable States: %s";

  public static WorkerFactory newInstance(WorkflowClient workflowClient) {
    return WorkerFactory.newInstance(workflowClient, WorkerFactoryOptions.getDefaultInstance());
  }

  public static WorkerFactory newInstance(
      WorkflowClient workflowClient, WorkerFactoryOptions options) {
    return new WorkerFactory(workflowClient, options);
  }

  /**
   * Creates a factory. Workers will connect to the temporal server using the workflowService client
   * passed in.
   *
   * @param workflowClient client to the Temporal Service endpoint.
   * @param factoryOptions Options used to configure factory settings
   */
  private WorkerFactory(WorkflowClient workflowClient, WorkerFactoryOptions factoryOptions) {
    this.workflowClient = Objects.requireNonNull(workflowClient);
    WorkflowClientOptions workflowClientOptions = workflowClient.getOptions();
    String namespace = workflowClientOptions.getNamespace();

    this.factoryOptions =
        WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();

    this.metricsScope =
        this.workflowClient
            .getWorkflowServiceStubs()
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(namespace));

    this.workflowThreadPool =
        new ThreadPoolExecutor(
            0,
            this.factoryOptions.getMaxWorkflowThreadCount(),
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>());
    this.workflowThreadPool.setThreadFactory(
        r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));
    this.workflowThreadExecutor =
        newWorkflowThreadExecutor(this.workflowThreadPool, this.metricsScope);

    this.cache =
        new WorkflowExecutorCache(this.factoryOptions.getWorkflowCacheSize(), metricsScope);

    this.stickyPoller =
        new StickyPoller(
            workflowClient.getWorkflowServiceStubs(),
            getStickyTaskQueueName(),
            this.factoryOptions.getWorkflowHostLocalPollThreadCount(),
            workflowClientOptions,
            metricsScope);
  }

  /**
   * Creates worker that connects to an instance of the Temporal Service. It uses the namespace
   * configured at the Factory level. New workers cannot be created after the start() has been
   * called
   *
   * @param taskQueue task queue name worker uses to poll. It uses this name for both workflow and
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
   * @param taskQueue task queue name worker uses to poll. It uses this name for both workflow and
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

    // Only one worker can exist for a task queue
    Worker existingWorker = workers.get(taskQueue);
    if (existingWorker == null) {
      Worker worker =
          new Worker(
              workflowClient,
              taskQueue,
              factoryOptions,
              options,
              metricsScope,
              cache,
              getStickyTaskQueueName(),
              workflowThreadExecutor,
              workflowClient.getOptions().getContextPropagators());
      workers.put(taskQueue, worker);
      return worker;
    } else {
      log.warn(
          "Only one worker can be registered for a task queue, "
              + "subsequent calls to WorkerFactory#newWorker with the same task queue are ignored and "
              + "initially created worker is returned");
      return existingWorker;
    }
  }

  /**
   * Returns a worker created previously through {@link #newWorker(String)} for the given task
   * queue.
   */
  public synchronized Worker getWorker(String taskQueue) {
    Worker result = workers.get(taskQueue);
    if (result == null) {
      throw new IllegalArgumentException("No worker for taskQueue: " + taskQueue);
    }
    return result;
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

    // Workers check and require that Temporal Server is available during start to fail-fast in case
    // of configuration issues.
    workflowClient.getWorkflowServiceStubs().connect(null);

    for (Worker worker : workers.values()) {
      worker.start();
      if (worker.workflowWorker.isStarted()) {
        stickyPoller.subscribe(worker.getTaskQueue(), worker.workflowWorker);
      }
    }

    if (stickyPoller != null) {
      stickyPoller.start();
    }

    state = State.Started;
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
    for (Worker worker : workers.values()) {
      if (!worker.isTerminated()) {
        return false;
      }
    }
    return true;
  }

  /**
   * @return instance of the Temporal client that this worker uses.
   */
  public WorkflowClient getWorkflowClient() {
    return workflowClient;
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received workflow and
   * activity tasks are executed. <br>
   * After the shutdown, calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}.<br>
   * This method does not wait for the shutdown to complete. Use {@link #awaitTermination(long,
   * TimeUnit)} to do that.<br>
   * Invocation has no additional effect if already shut down.
   */
  public synchronized void shutdown() {
    log.info("shutdown: {}", this);
    shutdownInternal(false);
  }

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received workflow and
   * activity tasks are attempted to be stopped. <br>
   * This implementation cancels tasks via Thread.interrupt(), so any task that fails to respond to
   * interrupts may never terminate.<br>
   * After the shutdownNow calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}.<br>
   * This method does not wait for the shutdown to complete. Use {@link #awaitTermination(long,
   * TimeUnit)} to do that.<br>
   * Invocation has no additional effect if already shut down.
   */
  public synchronized void shutdownNow() {
    log.info("shutdownNow: {}", this);
    shutdownInternal(true);
  }

  private void shutdownInternal(boolean interruptUserTasks) {
    state = State.Shutdown;
    ShutdownManager shutdownManager = new ShutdownManager();
    stickyPoller
        .shutdown(shutdownManager, interruptUserTasks)
        .thenCompose(
            ignore ->
                CompletableFuture.allOf(
                    workers.values().stream()
                        .map(worker -> worker.shutdown(shutdownManager, interruptUserTasks))
                        .toArray(CompletableFuture[]::new)))
        .thenApply(
            r -> {
              cache.invalidateAll();
              workflowThreadPool.shutdownNow();
              return null;
            })
        .whenComplete(
            (r, e) -> {
              if (e != null) {
                log.error("[BUG] Unexpected exception during shutdown", e);
              }
              shutdownManager.close();
            });
  }

  /**
   * Blocks until all tasks have completed execution after a shutdown request, or the timeout
   * occurs.
   */
  public void awaitTermination(long timeout, TimeUnit unit) {
    log.info("awaitTermination begin: {}", this);
    long timeoutMillis = unit.toMillis(timeout);
    timeoutMillis = ShutdownManager.awaitTermination(stickyPoller, timeoutMillis);
    for (Worker worker : workers.values()) {
      long t = timeoutMillis; // closure needs immutable value
      timeoutMillis =
          ShutdownManager.runAndGetRemainingTimeoutMs(
              t, () -> worker.awaitTermination(t, TimeUnit.MILLISECONDS));
    }
    log.info("awaitTermination done: {}", this);
  }

  // TODO we should hide an actual implementation of WorkerFactory under WorkerFactory interface and
  // expose this method on the implementation only
  @VisibleForTesting
  WorkflowExecutorCache getCache() {
    return this.cache;
  }

  private String getStickyTaskQueueName() {
    return String.format("%s:%s", workflowClient.getOptions().getIdentity(), id);
  }

  public synchronized void suspendPolling() {
    if (state != State.Started) {
      return;
    }

    log.info("suspendPolling: {}", this);
    state = State.Suspended;
    if (stickyPoller != null) {
      stickyPoller.suspendPolling();
    }
    for (Worker worker : workers.values()) {
      worker.suspendPolling();
    }
  }

  public synchronized void resumePolling() {
    if (state != State.Suspended) {
      return;
    }

    log.info("resumePolling: {}", this);
    state = State.Started;
    if (stickyPoller != null) {
      stickyPoller.resumePolling();
    }
    for (Worker worker : workers.values()) {
      worker.resumePolling();
    }
  }

  @Override
  public String toString() {
    return String.format(
        "WorkerFactory{identity=%s, uniqueId=%s}", workflowClient.getOptions().getIdentity(), id);
  }

  private static WorkflowThreadExecutor newWorkflowThreadExecutor(
      ThreadPoolExecutor workflowThreadPool, Scope metricsScope) {
    return r ->
        workflowThreadPool.submit(
            () -> {
              metricsScope
                  .gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT)
                  .update(workflowThreadPool.getActiveCount());
              try {
                r.run();
              } finally {
                metricsScope
                    .gauge(MetricsType.WORKFLOW_ACTIVE_THREAD_COUNT)
                    .update(workflowThreadPool.getActiveCount() - 1);
              }
            });
  }

  enum State {
    Initial,
    Started,
    Suspended,
    Shutdown
  }
}
