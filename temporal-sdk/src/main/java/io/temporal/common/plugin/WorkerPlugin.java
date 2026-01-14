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

package io.temporal.common.plugin;

import io.temporal.common.Experimental;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal worker configuration and lifecycle.
 *
 * <p>WorkerPlugins that also implement {@link ClientPlugin} are automatically propagated from the
 * client to workers created from that client.
 *
 * <p>Example implementation:
 *
 * <pre>{@code
 * public class MetricsPlugin extends PluginBase {
 *     private final MetricsRegistry registry;
 *
 *     public MetricsPlugin(MetricsRegistry registry) {
 *         super("my-org.metrics");
 *         this.registry = registry;
 *     }
 *
 *     @Override
 *     public WorkerFactoryOptions.Builder configureWorkerFactory(
 *             WorkerFactoryOptions.Builder builder) {
 *         return builder.setWorkerInterceptors(new MetricsWorkerInterceptor(registry));
 *     }
 *
 *     @Override
 *     public void runWorkerFactory(WorkerFactory factory, Runnable next) throws Exception {
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
 * @see ClientPlugin
 * @see PluginBase
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
   * @return the modified builder
   */
  @Nonnull
  default WorkerFactoryOptions.Builder configureWorkerFactory(
      @Nonnull WorkerFactoryOptions.Builder builder) {
    return builder;
  }

  /**
   * Allows the plugin to modify worker options before a worker is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param taskQueue the task queue name for the worker being created
   * @param builder the options builder to modify
   * @return the modified builder
   */
  @Nonnull
  default WorkerOptions.Builder configureWorker(
      @Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {
    return builder;
  }

  /**
   * Called after a worker is created, allowing plugins to register workflows, activities, Nexus
   * services, and other components on the worker.
   *
   * <p>This method is called in forward (registration) order immediately after the worker is
   * created in {@link WorkerFactory#newWorker}.
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
  default void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    // Default: no-op
  }

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
   * public void runWorkerFactory(WorkerFactory factory, Runnable next) throws Exception {
   *     logger.info("Starting workers...");
   *     next.run();
   *     logger.info("Workers started");
   * }
   * }</pre>
   *
   * @param factory the worker factory being started
   * @param next runnable that starts the next in chain (eventually starts actual workers)
   * @throws Exception if startup fails
   */
  default void runWorkerFactory(@Nonnull WorkerFactory factory, @Nonnull Runnable next)
      throws Exception {
    next.run();
  }

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
  default void shutdownWorkerFactory(@Nonnull WorkerFactory factory, @Nonnull Runnable next) {
    next.run();
  }
}
