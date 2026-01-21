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

package io.temporal.common;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowClientPlugin;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubsPlugin;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkerPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * A plugin that implements {@link WorkflowServiceStubsPlugin}, {@link WorkflowClientPlugin}, {@link
 * ScheduleClientPlugin}, and {@link WorkerPlugin}. This class can be used in two ways:
 *
 * <ol>
 *   <li><b>Builder pattern:</b> Use {@link #newBuilder(String)} to declaratively configure a plugin
 *       with callbacks
 *   <li><b>Subclassing:</b> Extend this class and override specific methods for custom behavior
 * </ol>
 *
 * <h3>Builder Pattern Example</h3>
 *
 * <pre>{@code
 * SimplePlugin myPlugin = SimplePlugin.newBuilder("my-plugin")
 *     .addWorkerInterceptors(new TracingInterceptor())
 *     .addClientInterceptors(new LoggingInterceptor())
 *     .customizeClient(b -> b.setIdentity("custom-identity"))
 *     .build();
 * }</pre>
 *
 * <h3>Subclassing Example</h3>
 *
 * <pre>{@code
 * public class TracingPlugin extends SimplePlugin {
 *     private final Tracer tracer;
 *
 *     public TracingPlugin(Tracer tracer) {
 *         super("io.temporal.tracing");
 *         this.tracer = tracer;
 *     }
 *
 *     @Override
 *     public void configureWorkflowClient(WorkflowClientOptions.Builder builder) {
 *         builder.setInterceptors(new TracingClientInterceptor(tracer));
 *     }
 * }
 * }</pre>
 *
 * <h3>Hybrid Example (Builder + Override)</h3>
 *
 * <pre>{@code
 * public class HybridPlugin extends SimplePlugin {
 *     public HybridPlugin() {
 *         super(SimplePlugin.newBuilder("hybrid")
 *             .addClientInterceptors(new LoggingInterceptor()));
 *     }
 *
 *     @Override
 *     public void initializeWorker(String taskQueue, Worker worker) {
 *         worker.registerWorkflowImplementationTypes(MyWorkflow.class);
 *     }
 * }
 * }</pre>
 *
 * @see WorkflowServiceStubsPlugin
 * @see WorkflowClientPlugin
 * @see ScheduleClientPlugin
 * @see WorkerPlugin
 */
@Experimental
public abstract class SimplePlugin
    implements WorkflowServiceStubsPlugin,
        WorkflowClientPlugin,
        ScheduleClientPlugin,
        WorkerPlugin {

  private final String name;
  private final List<Consumer<WorkflowServiceStubsOptions.Builder>> stubsCustomizers;
  private final List<Consumer<WorkflowClientOptions.Builder>> clientCustomizers;
  private final List<Consumer<ScheduleClientOptions.Builder>> scheduleCustomizers;
  private final List<Consumer<WorkerFactoryOptions.Builder>> factoryCustomizers;
  private final List<Consumer<WorkerOptions.Builder>> workerCustomizers;
  private final List<BiConsumer<String, Worker>> workerInitializers;
  private final List<BiConsumer<String, Worker>> workerStartCallbacks;
  private final List<BiConsumer<String, Worker>> workerShutdownCallbacks;
  private final List<Consumer<WorkerFactory>> workerFactoryStartCallbacks;
  private final List<Consumer<WorkerFactory>> workerFactoryShutdownCallbacks;
  private final List<BiConsumer<Worker, WorkflowExecutionHistory>> replayExecutionCallbacks;
  private final List<WorkerInterceptor> workerInterceptors;
  private final List<WorkflowClientInterceptor> clientInterceptors;
  private final List<ContextPropagator> contextPropagators;
  private final DataConverter dataConverter;
  private final List<Class<?>> workflowImplementationTypes;
  private final List<Object> activitiesImplementations;
  private final List<Object> nexusServiceImplementations;

  /**
   * Creates a new plugin with the specified name. Use this constructor when subclassing to override
   * specific methods.
   *
   * @param name a unique name for this plugin, used for logging and duplicate detection.
   *     Recommended format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   * @throws NullPointerException if name is null
   */
  protected SimplePlugin(@Nonnull String name) {
    this.name = Objects.requireNonNull(name, "Plugin name cannot be null");
    this.stubsCustomizers = Collections.emptyList();
    this.clientCustomizers = Collections.emptyList();
    this.scheduleCustomizers = Collections.emptyList();
    this.factoryCustomizers = Collections.emptyList();
    this.workerCustomizers = Collections.emptyList();
    this.workerInitializers = Collections.emptyList();
    this.workerStartCallbacks = Collections.emptyList();
    this.workerShutdownCallbacks = Collections.emptyList();
    this.workerFactoryStartCallbacks = Collections.emptyList();
    this.workerFactoryShutdownCallbacks = Collections.emptyList();
    this.replayExecutionCallbacks = Collections.emptyList();
    this.workerInterceptors = Collections.emptyList();
    this.clientInterceptors = Collections.emptyList();
    this.contextPropagators = Collections.emptyList();
    this.dataConverter = null;
    this.workflowImplementationTypes = Collections.emptyList();
    this.activitiesImplementations = Collections.emptyList();
    this.nexusServiceImplementations = Collections.emptyList();
  }

  /**
   * Creates a new plugin from a builder. Use this constructor when subclassing to combine builder
   * configuration with method overrides.
   *
   * @param builder the builder with configuration
   * @throws NullPointerException if builder is null
   */
  protected SimplePlugin(@Nonnull Builder builder) {
    Objects.requireNonNull(builder, "Builder cannot be null");
    this.name = builder.name;
    this.stubsCustomizers = new ArrayList<>(builder.stubsCustomizers);
    this.clientCustomizers = new ArrayList<>(builder.clientCustomizers);
    this.scheduleCustomizers = new ArrayList<>(builder.scheduleCustomizers);
    this.factoryCustomizers = new ArrayList<>(builder.factoryCustomizers);
    this.workerCustomizers = new ArrayList<>(builder.workerCustomizers);
    this.workerInitializers = new ArrayList<>(builder.workerInitializers);
    this.workerStartCallbacks = new ArrayList<>(builder.workerStartCallbacks);
    this.workerShutdownCallbacks = new ArrayList<>(builder.workerShutdownCallbacks);
    this.workerFactoryStartCallbacks = new ArrayList<>(builder.workerFactoryStartCallbacks);
    this.workerFactoryShutdownCallbacks = new ArrayList<>(builder.workerFactoryShutdownCallbacks);
    this.replayExecutionCallbacks = new ArrayList<>(builder.replayExecutionCallbacks);
    this.workerInterceptors = new ArrayList<>(builder.workerInterceptors);
    this.clientInterceptors = new ArrayList<>(builder.clientInterceptors);
    this.contextPropagators = new ArrayList<>(builder.contextPropagators);
    this.dataConverter = builder.dataConverter;
    this.workflowImplementationTypes = new ArrayList<>(builder.workflowImplementationTypes);
    this.activitiesImplementations = new ArrayList<>(builder.activitiesImplementations);
    this.nexusServiceImplementations = new ArrayList<>(builder.nexusServiceImplementations);
  }

  /**
   * Creates a new builder with the specified plugin name.
   *
   * @param name a unique name for the plugin, used for logging and duplicate detection. Recommended
   *     format: "organization.plugin-name" (e.g., "my-org.tracing")
   * @return a new builder instance
   */
  public static Builder newBuilder(@Nonnull String name) {
    return new Builder(name);
  }

  @Override
  @Nonnull
  public String getName() {
    return name;
  }

  @Override
  public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
    for (Consumer<WorkflowServiceStubsOptions.Builder> customizer : stubsCustomizers) {
      customizer.accept(builder);
    }
  }

  @Override
  public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
    // Apply customizers
    for (Consumer<WorkflowClientOptions.Builder> customizer : clientCustomizers) {
      customizer.accept(builder);
    }

    // Set data converter
    if (dataConverter != null) {
      builder.setDataConverter(dataConverter);
    }

    // Add client interceptors
    if (!clientInterceptors.isEmpty()) {
      WorkflowClientInterceptor[] existing = builder.build().getInterceptors();
      List<WorkflowClientInterceptor> combined =
          new ArrayList<>(existing != null ? Arrays.asList(existing) : new ArrayList<>());
      combined.addAll(clientInterceptors);
      builder.setInterceptors(combined.toArray(new WorkflowClientInterceptor[0]));
    }

    // Add context propagators
    if (!contextPropagators.isEmpty()) {
      List<ContextPropagator> existing = builder.build().getContextPropagators();
      List<ContextPropagator> combined =
          new ArrayList<>(existing != null ? existing : new ArrayList<>());
      combined.addAll(contextPropagators);
      builder.setContextPropagators(combined);
    }
  }

  @Override
  public void configureScheduleClient(@Nonnull ScheduleClientOptions.Builder builder) {
    // Apply customizers
    for (Consumer<ScheduleClientOptions.Builder> customizer : scheduleCustomizers) {
      customizer.accept(builder);
    }
  }

  @Override
  public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
    // Apply customizers
    for (Consumer<WorkerFactoryOptions.Builder> customizer : factoryCustomizers) {
      customizer.accept(builder);
    }

    // Add worker interceptors
    if (!workerInterceptors.isEmpty()) {
      WorkerInterceptor[] existing = builder.build().getWorkerInterceptors();
      List<WorkerInterceptor> combined =
          new ArrayList<>(existing != null ? Arrays.asList(existing) : new ArrayList<>());
      combined.addAll(workerInterceptors);
      builder.setWorkerInterceptors(combined.toArray(new WorkerInterceptor[0]));
    }
  }

  @Override
  public void configureWorker(@Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {
    for (Consumer<WorkerOptions.Builder> customizer : workerCustomizers) {
      customizer.accept(builder);
    }
  }

  @Override
  public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
    // Register workflow implementation types
    if (!workflowImplementationTypes.isEmpty()) {
      worker.registerWorkflowImplementationTypes(
          workflowImplementationTypes.toArray(new Class<?>[0]));
    }

    // Register activities implementations
    if (!activitiesImplementations.isEmpty()) {
      worker.registerActivitiesImplementations(activitiesImplementations.toArray());
    }

    // Register nexus service implementations
    for (Object nexusService : nexusServiceImplementations) {
      worker.registerNexusServiceImplementation(nexusService);
    }

    // Apply custom initializers
    for (BiConsumer<String, Worker> initializer : workerInitializers) {
      initializer.accept(taskQueue, worker);
    }
  }

  @Override
  public void startWorker(
      @Nonnull String taskQueue, @Nonnull Worker worker, @Nonnull Runnable next) {
    next.run();
    for (BiConsumer<String, Worker> callback : workerStartCallbacks) {
      callback.accept(taskQueue, worker);
    }
  }

  @Override
  public void shutdownWorker(
      @Nonnull String taskQueue, @Nonnull Worker worker, @Nonnull Runnable next) {
    for (BiConsumer<String, Worker> callback : workerShutdownCallbacks) {
      callback.accept(taskQueue, worker);
    }
    next.run();
  }

  @Override
  public WorkflowServiceStubs connectServiceClient(
      WorkflowServiceStubsOptions options, Supplier<WorkflowServiceStubs> next) {
    return next.get();
  }

  @Override
  public void startWorkerFactory(WorkerFactory factory, Runnable next) {
    next.run();
    for (Consumer<WorkerFactory> callback : workerFactoryStartCallbacks) {
      callback.accept(factory);
    }
  }

  @Override
  public void shutdownWorkerFactory(WorkerFactory factory, Runnable next) {
    for (Consumer<WorkerFactory> callback : workerFactoryShutdownCallbacks) {
      callback.accept(factory);
    }
    next.run();
  }

  @Override
  public void replayWorkflowExecution(
      @Nonnull Worker worker,
      @Nonnull WorkflowExecutionHistory history,
      @Nonnull Callable<Void> next)
      throws Exception {
    next.call();
    for (BiConsumer<Worker, WorkflowExecutionHistory> callback : replayExecutionCallbacks) {
      callback.accept(worker, history);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{name='" + name + "'}";
  }

  /** Builder for creating {@link SimplePlugin} instances with declarative configuration. */
  public static final class Builder {

    private final String name;
    private final List<Consumer<WorkflowServiceStubsOptions.Builder>> stubsCustomizers =
        new ArrayList<>();
    private final List<Consumer<WorkflowClientOptions.Builder>> clientCustomizers =
        new ArrayList<>();
    private final List<Consumer<ScheduleClientOptions.Builder>> scheduleCustomizers =
        new ArrayList<>();
    private final List<Consumer<WorkerFactoryOptions.Builder>> factoryCustomizers =
        new ArrayList<>();
    private final List<Consumer<WorkerOptions.Builder>> workerCustomizers = new ArrayList<>();
    private final List<BiConsumer<String, Worker>> workerInitializers = new ArrayList<>();
    private final List<BiConsumer<String, Worker>> workerStartCallbacks = new ArrayList<>();
    private final List<BiConsumer<String, Worker>> workerShutdownCallbacks = new ArrayList<>();
    private final List<Consumer<WorkerFactory>> workerFactoryStartCallbacks = new ArrayList<>();
    private final List<Consumer<WorkerFactory>> workerFactoryShutdownCallbacks = new ArrayList<>();
    private final List<BiConsumer<Worker, WorkflowExecutionHistory>> replayExecutionCallbacks =
        new ArrayList<>();
    private final List<WorkerInterceptor> workerInterceptors = new ArrayList<>();
    private final List<WorkflowClientInterceptor> clientInterceptors = new ArrayList<>();
    private final List<ContextPropagator> contextPropagators = new ArrayList<>();
    private DataConverter dataConverter;
    private final List<Class<?>> workflowImplementationTypes = new ArrayList<>();
    private final List<Object> activitiesImplementations = new ArrayList<>();
    private final List<Object> nexusServiceImplementations = new ArrayList<>();

    private Builder(@Nonnull String name) {
      this.name = Objects.requireNonNull(name, "Plugin name cannot be null");
    }

    /**
     * Adds a customizer for {@link WorkflowServiceStubsOptions}. Multiple customizers are applied
     * in the order they are added.
     *
     * @param customizer a consumer that modifies the options builder
     * @return this builder for chaining
     */
    public Builder customizeServiceStubs(
        @Nonnull Consumer<WorkflowServiceStubsOptions.Builder> customizer) {
      stubsCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /**
     * Adds a customizer for {@link WorkflowClientOptions}. Multiple customizers are applied in the
     * order they are added.
     *
     * @param customizer a consumer that modifies the options builder
     * @return this builder for chaining
     */
    public Builder customizeClient(@Nonnull Consumer<WorkflowClientOptions.Builder> customizer) {
      clientCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /**
     * Adds a customizer for {@link ScheduleClientOptions}. Multiple customizers are applied in the
     * order they are added.
     *
     * @param customizer a consumer that modifies the options builder
     * @return this builder for chaining
     */
    public Builder customizeScheduleClient(
        @Nonnull Consumer<ScheduleClientOptions.Builder> customizer) {
      scheduleCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /**
     * Adds a customizer for {@link WorkerFactoryOptions}. Multiple customizers are applied in the
     * order they are added.
     *
     * @param customizer a consumer that modifies the options builder
     * @return this builder for chaining
     */
    public Builder customizeWorkerFactory(
        @Nonnull Consumer<WorkerFactoryOptions.Builder> customizer) {
      factoryCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /**
     * Adds a customizer for {@link WorkerOptions}. Multiple customizers are applied in the order
     * they are added. The customizer is applied to all workers created by the factory.
     *
     * @param customizer a consumer that modifies the options builder
     * @return this builder for chaining
     */
    public Builder customizeWorker(@Nonnull Consumer<WorkerOptions.Builder> customizer) {
      workerCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /**
     * Adds an initializer that is called after a worker is created. This can be used to register
     * workflows, activities, and Nexus services on the worker.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .initializeWorker((taskQueue, worker) -> {
     *         worker.registerWorkflowImplementationTypes(MyWorkflow.class);
     *         worker.registerActivitiesImplementations(new MyActivityImpl());
     *     })
     *     .build();
     * }</pre>
     *
     * @param initializer a consumer that receives the task queue name and worker
     * @return this builder for chaining
     */
    public Builder initializeWorker(@Nonnull BiConsumer<String, Worker> initializer) {
      workerInitializers.add(Objects.requireNonNull(initializer));
      return this;
    }

    /**
     * Adds a callback that is invoked when a worker starts. This can be used to start per-worker
     * resources or record metrics.
     *
     * <p>Note: For registering workflows and activities, use {@link #initializeWorker} instead, as
     * registrations must happen before the worker starts polling.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .onWorkerStart((taskQueue, worker) -> {
     *         logger.info("Worker started for task queue: {}", taskQueue);
     *         perWorkerResources.put(taskQueue, new ResourcePool());
     *     })
     *     .build();
     * }</pre>
     *
     * @param callback a consumer that receives the task queue name and worker when the worker
     *     starts
     * @return this builder for chaining
     */
    public Builder onWorkerStart(@Nonnull BiConsumer<String, Worker> callback) {
      workerStartCallbacks.add(Objects.requireNonNull(callback));
      return this;
    }

    /**
     * Adds a callback that is invoked when a worker shuts down. This can be used to clean up
     * per-worker resources initialized in {@link #initializeWorker} or {@link #onWorkerStart}.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .onWorkerShutdown((taskQueue, worker) -> {
     *         logger.info("Worker shutting down for task queue: {}", taskQueue);
     *         ResourcePool pool = perWorkerResources.remove(taskQueue);
     *         if (pool != null) {
     *             pool.close();
     *         }
     *     })
     *     .build();
     * }</pre>
     *
     * @param callback a consumer that receives the task queue name and worker when the worker shuts
     *     down
     * @return this builder for chaining
     */
    public Builder onWorkerShutdown(@Nonnull BiConsumer<String, Worker> callback) {
      workerShutdownCallbacks.add(Objects.requireNonNull(callback));
      return this;
    }

    /**
     * Adds a callback that is invoked when the worker factory starts. This can be used to
     * initialize factory-level resources or record metrics.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .onWorkerFactoryStart(factory -> {
     *         logger.info("Worker factory started");
     *         globalResources.initialize();
     *     })
     *     .build();
     * }</pre>
     *
     * @param callback a consumer that receives the worker factory when it starts
     * @return this builder for chaining
     */
    public Builder onWorkerFactoryStart(@Nonnull Consumer<WorkerFactory> callback) {
      workerFactoryStartCallbacks.add(Objects.requireNonNull(callback));
      return this;
    }

    /**
     * Adds a callback that is invoked when the worker factory shuts down. This can be used to clean
     * up factory-level resources.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .onWorkerFactoryShutdown(factory -> {
     *         logger.info("Worker factory shutting down");
     *         globalResources.cleanup();
     *     })
     *     .build();
     * }</pre>
     *
     * @param callback a consumer that receives the worker factory when it shuts down
     * @return this builder for chaining
     */
    public Builder onWorkerFactoryShutdown(@Nonnull Consumer<WorkerFactory> callback) {
      workerFactoryShutdownCallbacks.add(Objects.requireNonNull(callback));
      return this;
    }

    // ==================== Replay Methods ====================

    /**
     * Adds a callback that is invoked after a workflow execution is replayed. This can be used for
     * logging, metrics, or other observability around replay operations.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .onReplayWorkflowExecution((worker, history) -> {
     *         logger.info("Replayed workflow: {}", history.getWorkflowExecution().getWorkflowId());
     *     })
     *     .build();
     * }</pre>
     *
     * @param callback a consumer that receives the worker and history after replay completes
     * @return this builder for chaining
     */
    public Builder onReplayWorkflowExecution(
        @Nonnull BiConsumer<Worker, WorkflowExecutionHistory> callback) {
      replayExecutionCallbacks.add(Objects.requireNonNull(callback));
      return this;
    }

    /**
     * Adds worker interceptors. Interceptors are appended to any existing interceptors in the
     * configuration.
     *
     * @param interceptors the interceptors to add
     * @return this builder for chaining
     */
    public Builder addWorkerInterceptors(WorkerInterceptor... interceptors) {
      workerInterceptors.addAll(Arrays.asList(interceptors));
      return this;
    }

    /**
     * Adds client interceptors. Interceptors are appended to any existing interceptors in the
     * configuration.
     *
     * @param interceptors the interceptors to add
     * @return this builder for chaining
     */
    public Builder addClientInterceptors(WorkflowClientInterceptor... interceptors) {
      clientInterceptors.addAll(Arrays.asList(interceptors));
      return this;
    }

    /**
     * Adds context propagators. Propagators are appended to any existing propagators in the
     * configuration.
     *
     * @param propagators the propagators to add
     * @return this builder for chaining
     */
    public Builder addContextPropagators(ContextPropagator... propagators) {
      contextPropagators.addAll(Arrays.asList(propagators));
      return this;
    }

    /**
     * Sets the data converter to use for serializing workflow and activity arguments and results.
     * This overrides any data converter previously set on the client options.
     *
     * @param dataConverter the data converter to use
     * @return this builder for chaining
     */
    public Builder setDataConverter(@Nonnull DataConverter dataConverter) {
      this.dataConverter = Objects.requireNonNull(dataConverter);
      return this;
    }

    /**
     * Registers workflow implementation types. These workflows will be registered on all workers
     * created by the factory.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .registerWorkflowImplementationTypes(MyWorkflowImpl.class, OtherWorkflowImpl.class)
     *     .build();
     * }</pre>
     *
     * @param workflowImplementationTypes workflow implementation classes to register
     * @return this builder for chaining
     */
    public Builder registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes) {
      this.workflowImplementationTypes.addAll(Arrays.asList(workflowImplementationTypes));
      return this;
    }

    /**
     * Registers activity implementations. These activities will be registered on all workers
     * created by the factory.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .registerActivitiesImplementations(new MyActivityImpl(), new OtherActivityImpl())
     *     .build();
     * }</pre>
     *
     * @param activitiesImplementations activity implementation instances to register
     * @return this builder for chaining
     */
    public Builder registerActivitiesImplementations(Object... activitiesImplementations) {
      this.activitiesImplementations.addAll(Arrays.asList(activitiesImplementations));
      return this;
    }

    /**
     * Registers a Nexus service implementation. The service will be registered on all workers
     * created by the factory.
     *
     * <p>Example:
     *
     * <pre>{@code
     * SimplePlugin.newBuilder("my-plugin")
     *     .registerNexusServiceImplementation(new MyNexusServiceImpl())
     *     .build();
     * }</pre>
     *
     * @param nexusServiceImplementation the Nexus service implementation to register
     * @return this builder for chaining
     */
    public Builder registerNexusServiceImplementation(@Nonnull Object nexusServiceImplementation) {
      this.nexusServiceImplementations.add(Objects.requireNonNull(nexusServiceImplementation));
      return this;
    }

    /**
     * Builds the plugin with the configured settings.
     *
     * @return a new plugin instance
     */
    public SimplePlugin build() {
      return new SimplePluginImpl(this);
    }
  }

  /** Private concrete implementation returned by the builder. */
  private static final class SimplePluginImpl extends SimplePlugin {
    SimplePluginImpl(Builder builder) {
      super(builder);
    }
  }
}
