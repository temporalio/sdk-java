package io.temporal.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.internal.sync.WorkflowThreadExecutor;
import io.temporal.internal.task.VirtualThreadDelegate;
import io.temporal.internal.worker.ShutdownManager;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowRunLockManager;
import io.temporal.serviceclient.MetricsTag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains worker creation and lifecycle. */
public final class WorkerFactory {
  private static final Logger log = LoggerFactory.getLogger(WorkerFactory.class);

  private final WorkflowRunLockManager runLocks = new WorkflowRunLockManager();

  private final Scope metricsScope;

  private final Map<String, Worker> workers = new HashMap<>();
  private final WorkflowClient workflowClient;
  private final ExecutorService workflowThreadPool;
  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final AtomicInteger workflowThreadCounter = new AtomicInteger();
  private final WorkerFactoryOptions factoryOptions;

  private final @Nonnull WorkflowExecutorCache cache;

  /** Plugins propagated from the client and applied to this factory. */
  private final List<WorkerPlugin> plugins;

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

    // Extract worker plugins from client (auto-propagation)
    this.plugins = extractWorkerPlugins(workflowClientOptions.getPlugins());

    // Apply plugin configuration to factory options (forward order)
    factoryOptions = applyPluginConfiguration(factoryOptions, this.plugins);

    this.factoryOptions =
        WorkerFactoryOptions.newBuilder(factoryOptions).validateAndBuildWithDefaults();

    this.metricsScope =
        this.workflowClient
            .getWorkflowServiceStubs()
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(namespace));

    if (this.factoryOptions.isUsingVirtualWorkflowThreads()) {
      this.workflowThreadPool =
          VirtualThreadDelegate.newVirtualThreadExecutor(
              (t) -> t.setName("workflow-thread-" + workflowThreadCounter.incrementAndGet()));
    } else {
      ThreadPoolExecutor workflowThreadPoolExecutor =
          new ThreadPoolExecutor(
              0,
              this.factoryOptions.getMaxWorkflowThreadCount(),
              1,
              TimeUnit.MINUTES,
              new SynchronousQueue<>());
      workflowThreadPoolExecutor.setThreadFactory(
          r -> new Thread(r, "workflow-thread-" + workflowThreadCounter.incrementAndGet()));
      this.workflowThreadPool = workflowThreadPoolExecutor;
    }

    this.workflowThreadExecutor =
        new ActiveThreadReportingExecutor(this.workflowThreadPool, this.metricsScope);

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
   * @param options Options (like {@link DataConverter} override) for configuring worker.
   * @return Worker
   */
  public synchronized Worker newWorker(String taskQueue, WorkerOptions options) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(taskQueue), "taskQueue should not be an empty string");
    Preconditions.checkState(
        state == State.Initial,
        String.format(statusErrorMessage, "create new worker", state.name(), State.Initial.name()));

    // Apply plugin configuration to worker options (forward order)
    options = applyWorkerPluginConfiguration(taskQueue, options, this.plugins);

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
              runLocks,
              cache,
              true,
              workflowThreadExecutor,
              workflowClient.getOptions().getContextPropagators());
      workers.put(taskQueue, worker);

      // Go through the plugins to call plugin initializeWorker hooks (e.g. register workflows,
      // activities, etc.)
      for (WorkerPlugin plugin : plugins) {
        plugin.initializeWorker(taskQueue, worker);
      }

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
            String.format("%s, %s", State.Initial.name(), State.Started.name())));

    if (state == State.Started) {
      return;
    }

    // Workers check and require that Temporal Server is available during start to fail-fast in case
    // of configuration issues.
    workflowClient
        .getWorkflowServiceStubs()
        .blockingStub()
        .describeNamespace(
            DescribeNamespaceRequest.newBuilder()
                .setNamespace(workflowClient.getOptions().getNamespace())
                .build());

    // Build plugin execution chain (reverse order for proper nesting)
    Runnable startChain = this::doStart;
    for (int i = plugins.size() - 1; i >= 0; i--) {
      final Runnable next = startChain;
      final WorkerPlugin workerPlugin = plugins.get(i);
      startChain =
          () -> {
            try {
              workerPlugin.startWorkerFactory(this, next);
            } catch (RuntimeException e) {
              throw e;
            } catch (Exception e) {
              throw new RuntimeException(
                  "Plugin " + workerPlugin.getName() + " failed during startup", e);
            }
          };
    }

    // Execute the chain
    startChain.run();
  }

  /** Internal method that actually starts the workers. Called from the plugin chain. */
  private void doStart() {
    // Start each worker with plugin hooks
    for (Map.Entry<String, Worker> entry : workers.entrySet()) {
      String taskQueue = entry.getKey();
      Worker worker = entry.getValue();

      // Build plugin chain for this worker (reverse order for proper nesting)
      Runnable startChain = worker::start;
      for (int i = plugins.size() - 1; i >= 0; i--) {
        final Runnable next = startChain;
        final WorkerPlugin workerPlugin = plugins.get(i);
        startChain =
            () -> {
              try {
                workerPlugin.startWorker(taskQueue, worker, next);
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new RuntimeException(
                    "Plugin "
                        + workerPlugin.getName()
                        + " failed during worker startup for task queue "
                        + taskQueue,
                    e);
              }
            };
      }

      // Execute the chain for this worker
      startChain.run();
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

    // Build plugin shutdown chain (reverse order for proper nesting)
    Runnable shutdownChain = () -> doShutdown(interruptUserTasks);
    for (int i = plugins.size() - 1; i >= 0; i--) {
      final Runnable next = shutdownChain;
      final WorkerPlugin workerPlugin = plugins.get(i);
      shutdownChain =
          () -> {
            try {
              workerPlugin.shutdownWorkerFactory(this, next);
            } catch (Exception e) {
              log.warn("Plugin {} failed during shutdown", workerPlugin.getName(), e);
              // Still try to continue shutdown
              next.run();
            }
          };
    }

    // Execute the chain
    shutdownChain.run();
  }

  /** Internal method that actually shuts down workers. Called from the plugin chain. */
  private void doShutdown(boolean interruptUserTasks) {
    ((WorkflowClientInternal) workflowClient.getInternal()).deregisterWorkerFactory(this);
    ShutdownManager shutdownManager = new ShutdownManager();

    // Shutdown each worker with plugin hooks
    List<CompletableFuture<Void>> shutdownFutures = new ArrayList<>();
    for (Map.Entry<String, Worker> entry : workers.entrySet()) {
      String taskQueue = entry.getKey();
      Worker worker = entry.getValue();

      // Build plugin chain for this worker's shutdown (reverse order for proper nesting)
      // We use a holder to capture the future from the terminal action
      @SuppressWarnings("unchecked")
      CompletableFuture<Void>[] futureHolder = new CompletableFuture[1];
      Runnable shutdownChain =
          () -> futureHolder[0] = worker.shutdown(shutdownManager, interruptUserTasks);

      for (int i = plugins.size() - 1; i >= 0; i--) {
        final Runnable next = shutdownChain;
        final WorkerPlugin workerPlugin = plugins.get(i);
        shutdownChain =
            () -> {
              try {
                workerPlugin.shutdownWorker(taskQueue, worker, next);
              } catch (Exception e) {
                log.warn(
                    "Plugin {} failed during worker shutdown for task queue {}",
                    workerPlugin.getName(),
                    taskQueue,
                    e);
                // Still try to continue shutdown
                next.run();
              }
            };
      }

      // Execute the shutdown chain for this worker
      shutdownChain.run();
      if (futureHolder[0] != null) {
        shutdownFutures.add(futureHolder[0]);
      }
    }

    CompletableFuture.allOf(shutdownFutures.toArray(new CompletableFuture[0]))
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

  /**
   * Extracts worker plugins from the client plugins array. Only plugins that also implement {@link
   * WorkerPlugin} are included.
   */
  private static List<WorkerPlugin> extractWorkerPlugins(
      io.temporal.client.ClientPlugin[] clientPlugins) {
    if (clientPlugins == null || clientPlugins.length == 0) {
      return Collections.emptyList();
    }

    List<WorkerPlugin> workerPlugins = new ArrayList<>();
    for (io.temporal.client.ClientPlugin plugin : clientPlugins) {
      if (plugin instanceof WorkerPlugin) {
        workerPlugins.add((WorkerPlugin) plugin);
      }
    }
    return Collections.unmodifiableList(workerPlugins);
  }

  /**
   * Applies plugin configuration to worker factory options. Plugins are called in forward
   * (registration) order.
   */
  private static WorkerFactoryOptions applyPluginConfiguration(
      WorkerFactoryOptions options, List<WorkerPlugin> plugins) {
    if (plugins == null || plugins.isEmpty()) {
      return options;
    }

    WorkerFactoryOptions.Builder builder =
        options == null
            ? WorkerFactoryOptions.newBuilder()
            : WorkerFactoryOptions.newBuilder(options);

    for (WorkerPlugin plugin : plugins) {
      plugin.configureWorkerFactory(builder);
    }
    return builder.build();
  }

  /**
   * Applies plugin configuration to worker options. Plugins are called in forward (registration)
   * order.
   */
  private static WorkerOptions applyWorkerPluginConfiguration(
      String taskQueue, WorkerOptions options, List<WorkerPlugin> plugins) {
    if (plugins == null || plugins.isEmpty()) {
      return options;
    }

    WorkerOptions.Builder builder =
        options == null ? WorkerOptions.newBuilder() : WorkerOptions.newBuilder(options);

    for (WorkerPlugin plugin : plugins) {
      plugin.configureWorker(taskQueue, builder);
    }
    return builder.build();
  }

  enum State {
    Initial,
    Started,
    Suspended,
    Shutdown
  }
}
