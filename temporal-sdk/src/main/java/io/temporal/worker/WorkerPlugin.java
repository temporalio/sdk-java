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

import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.common.WorkflowExecutionHistory;
import java.util.concurrent.Callable;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal worker configuration and lifecycle.
 *
 * <p>Plugins that also implement {@link io.temporal.client.ClientPlugin} are automatically
 * propagated from the client to workers created from that client.
 *
 * <p>Example implementation:
 *
 * <pre>{@code
 * public class MetricsPlugin extends SimplePlugin {
 *     private final MetricsRegistry registry;
 *
 *     public MetricsPlugin(MetricsRegistry registry) {
 *         super("my-org.metrics");
 *         this.registry = registry;
 *     }
 *
 *     @Override
 *     public void configureWorkerFactory(WorkerFactoryOptions.Builder builder) {
 *         builder.setWorkerInterceptors(new MetricsWorkerInterceptor(registry));
 *     }
 *
 *     @Override
 *     public void startWorkerFactory(WorkerFactory factory, Runnable next) {
 *         registry.recordWorkerStart();
 *         try {
 *             next.run();
 *         } finally {
 *             registry.recordWorkerStop();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see io.temporal.client.ClientPlugin
 * @see SimplePlugin
 */
@Experimental
public interface WorkerPlugin {

  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify worker factory options before the factory is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   */
  void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder);

  /**
   * Allows the plugin to modify worker options before a worker is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param taskQueue the task queue name for the worker being created
   * @param builder the options builder to modify
   */
  void configureWorker(@Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder);

  /**
   * Called after a worker is created, allowing plugins to register workflows, activities, Nexus
   * services, and other components on the worker.
   *
   * <p>This method is called in forward (registration) order immediately after the worker is
   * created in {@link WorkerFactory#newWorker}. This is the appropriate place for registrations
   * because it is called before the worker starts polling.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void initializeWorker(String taskQueue, Worker worker) {
   *     worker.registerWorkflowImplementationTypes(MyWorkflow.class);
   *     worker.registerActivitiesImplementations(new MyActivityImpl());
   * }
   * }</pre>
   *
   * @param taskQueue the task queue name for the worker
   * @param worker the newly created worker
   */
  void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker);

  /**
   * Allows the plugin to wrap individual worker startup. Called during execution phase in reverse
   * order (first plugin wraps all others) when {@link WorkerFactory#start()} is invoked.
   *
   * <p>This method is called for each worker when the factory starts. Use this for per-worker
   * resource initialization, logging, or metrics. Note that workflow/activity registration should
   * be done in {@link #initializeWorker} instead, as this method is called after registrations are
   * finalized.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void startWorker(String taskQueue, Worker worker, Runnable next) {
   *     logger.info("Starting worker for task queue: {}", taskQueue);
   *     perWorkerResources.put(taskQueue, new ResourcePool());
   *     next.run();
   * }
   * }</pre>
   *
   * @param taskQueue the task queue name for the worker
   * @param worker the worker being started
   * @param next runnable that starts the next in chain (eventually starts the actual worker)
   */
  void startWorker(@Nonnull String taskQueue, @Nonnull Worker worker, @Nonnull Runnable next);

  /**
   * Allows the plugin to wrap individual worker shutdown. Called during shutdown phase in reverse
   * order (first plugin wraps all others) when {@link WorkerFactory#shutdown()} or {@link
   * WorkerFactory#shutdownNow()} is invoked.
   *
   * <p>This method is called for each worker when the factory shuts down. Use this for per-worker
   * resource cleanup that was initialized in {@link #startWorker} or {@link #initializeWorker}.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void shutdownWorker(String taskQueue, Worker worker, Runnable next) {
   *     logger.info("Shutting down worker for task queue: {}", taskQueue);
   *     next.run();
   *     ResourcePool pool = perWorkerResources.remove(taskQueue);
   *     if (pool != null) {
   *         pool.close();
   *     }
   * }
   * }</pre>
   *
   * @param taskQueue the task queue name for the worker
   * @param worker the worker being shut down
   * @param next runnable that shuts down the next in chain (eventually shuts down the actual
   *     worker)
   */
  void shutdownWorker(@Nonnull String taskQueue, @Nonnull Worker worker, @Nonnull Runnable next);

  /**
   * Allows the plugin to wrap worker factory startup. Called during execution phase in reverse
   * order (first plugin wraps all others).
   *
   * <p>This method is called when {@link WorkerFactory#start()} is invoked. The plugin can perform
   * setup before starting and cleanup logic.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void startWorkerFactory(WorkerFactory factory, Runnable next) {
   *     logger.info("Starting workers...");
   *     next.run();
   *     logger.info("Workers started");
   * }
   * }</pre>
   *
   * @param factory the worker factory being started
   * @param next runnable that starts the next in chain (eventually starts actual workers)
   */
  void startWorkerFactory(@Nonnull WorkerFactory factory, @Nonnull Runnable next);

  /**
   * Allows the plugin to wrap worker factory shutdown. Called during shutdown phase in reverse
   * order (first plugin wraps all others).
   *
   * <p>This method is called when {@link WorkerFactory#shutdown()} or {@link
   * WorkerFactory#shutdownNow()} is invoked. The plugin can perform actions before and after the
   * actual shutdown occurs.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void shutdownWorkerFactory(WorkerFactory factory, Runnable next) {
   *     logger.info("Shutting down workers...");
   *     next.run();
   *     logger.info("Workers shut down");
   * }
   * }</pre>
   *
   * @param factory the worker factory being shut down
   * @param next runnable that shuts down the next in chain (eventually shuts down actual workers)
   */
  void shutdownWorkerFactory(@Nonnull WorkerFactory factory, @Nonnull Runnable next);

  // ==================== Replay Methods ====================

  /**
   * Allows the plugin to wrap workflow execution replay. Called in reverse order (first plugin
   * wraps all others) when replaying a workflow history.
   *
   * <p>This method allows plugins to perform setup/teardown around replay, add logging, metrics, or
   * other observability for replay operations.
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public void replayWorkflowExecution(
   *     Worker worker, WorkflowExecutionHistory history, Callable<Void> next) throws Exception {
   *     logger.info("Replaying workflow: {}", history.getWorkflowExecution().getWorkflowId());
   *     long start = System.currentTimeMillis();
   *     try {
   *         next.call();
   *         logger.info("Replay succeeded in {}ms", System.currentTimeMillis() - start);
   *     } catch (Exception e) {
   *         logger.error("Replay failed after {}ms", System.currentTimeMillis() - start, e);
   *         throw e;
   *     }
   * }
   * }</pre>
   *
   * @param worker the worker performing the replay
   * @param history the workflow execution history being replayed
   * @param next callable that performs the next in chain (eventually performs the actual replay)
   * @throws Exception if replay fails
   */
  void replayWorkflowExecution(
      @Nonnull Worker worker,
      @Nonnull WorkflowExecutionHistory history,
      @Nonnull Callable<Void> next)
      throws Exception;
}
