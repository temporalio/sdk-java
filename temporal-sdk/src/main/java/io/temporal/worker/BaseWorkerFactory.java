package io.temporal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.worker.ShutdownManager;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.MetricsTag;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseWorkerFactory {
  private static final Logger log = LoggerFactory.getLogger(WorkerFactory.class);
  private final WorkflowRunLockManager runLocks = new WorkflowRunLockManager();
  protected final Scope metricsScope;
  private final WorkflowClient workflowClient;
  // TODO(maxim): Move factory options down into WorkerFactory.
  //  This requires moving ActivityTaskHandler creation there as well.
  private final WorkerFactoryOptions factoryOptions;
  private final @Nonnull WorkflowExecutorCache cache;
  private final Map<String, Worker> workers = new HashMap<>();
  private final String statusErrorMessage =
      "attempted to %s while in %s state. Acceptable States: %s";
  private State state = State.Initial;

  protected BaseWorkerFactory(WorkflowClient workflowClient, WorkerFactoryOptions factoryOptions) {
    this.workflowClient = Objects.requireNonNull(workflowClient);
    this.factoryOptions =
        WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();
    WorkflowClientOptions workflowClientOptions = workflowClient.getOptions();

    String namespace = workflowClientOptions.getNamespace();
    this.metricsScope =
        this.workflowClient
            .getWorkflowServiceStubs()
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(namespace));
    this.cache =
        new WorkflowExecutorCache(
            this.factoryOptions.getWorkflowCacheSize(), runLocks, metricsScope);
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
   * @param workerOptions Options (like {@link DataConverter} override) for configuring worker.
   * @return Worker
   */
  public synchronized Worker newWorker(String taskQueue, WorkerOptions workerOptions) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskQueue), "taskQueue should not be an empty string");
    Preconditions.checkState(
        state == State.Initial,
        String.format(statusErrorMessage, "create new worker", state.name(), State.Initial.name()));

    workerOptions = WorkerOptions.newBuilder(workerOptions).validateAndBuildWithDefaults();
    // Only one worker can exist for a task queue
    Worker existingWorker = workers.get(taskQueue);
    if (existingWorker == null) {
      WorkflowClientOptions clientOptions = workflowClient.getOptions();
      ReplayWorkflowFactory workflowFactory =
          newReplayWorkflowFactory(workerOptions, clientOptions, cache);

      Worker worker =
          new Worker(
              workflowClient,
              taskQueue,
              factoryOptions,
              workerOptions,
              metricsScope,
              runLocks,
              cache,
              true,
              clientOptions.getContextPropagators(),
              workflowFactory);
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

  protected abstract ReplayWorkflowFactory newReplayWorkflowFactory(
      WorkerOptions workerOptions,
      WorkflowClientOptions clientOptions,
      WorkflowExecutorCache cache1);

  /**
   * @param taskQueue task queue name to lookup an existing worker for
   * @return a worker created previously through {@link #newWorker(String)} for the given task
   *     queue.
   * @throws IllegalStateException if the worker has not been registered for the given task queue.
   */
  public synchronized Worker getWorker(String taskQueue) {
    Worker result = workers.get(taskQueue);
    if (result == null) {
      throw new IllegalArgumentException("No worker for taskQueue: " + taskQueue);
    }
    return result;
  }

  /**
   * @param taskQueue task queue name to lookup an existing worker for
   * @return a worker created previously through {@link #newWorker(String)} for the given task queue
   *     or null.
   */
  @Nullable
  public synchronized Worker tryGetWorker(@Nonnull String taskQueue) {
    return workers.get(taskQueue);
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
    }

    state = State.Started;
    ((WorkflowClientInternal) workflowClient.getInternal()).registerWorkerFactory(this);
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
    for (Worker worker : workers.values()) {
      if (!worker.isTerminated()) {
        return false;
      }
    }
    return true;
  }

  /**
   * @return instance of the Temporal client that this worker factory uses.
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
    ((WorkflowClientInternal) workflowClient.getInternal()).deregisterWorkerFactory(this);
    ShutdownManager shutdownManager = new ShutdownManager();
    CompletableFuture.allOf(
            workers.values().stream()
                .map(worker -> worker.shutdown(shutdownManager, interruptUserTasks))
                .toArray(CompletableFuture[]::new))
        .thenApply(
            r -> {
              cache.invalidateAll();
              handleShutdown();
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

  /** Override to clean resources upon shutdown request. */
  protected void handleShutdown() {}

  /**
   * Blocks until all tasks have completed execution after a shutdown request, or the timeout
   * occurs.
   */
  public void awaitTermination(long timeout, TimeUnit unit) {
    log.info("awaitTermination begin: {}", this);
    long timeoutMillis = unit.toMillis(timeout);
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

  public synchronized void suspendPolling() {
    if (state != State.Started) {
      return;
    }

    log.info("suspendPolling: {}", this);
    state = State.Suspended;
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
    for (Worker worker : workers.values()) {
      worker.resumePolling();
    }
  }

  @Override
  public String toString() {
    return String.format("WorkerFactory{identity=%s}", workflowClient.getOptions().getIdentity());
  }

  enum State {
    Initial,
    Started,
    Suspended,
    Shutdown
  }
}
