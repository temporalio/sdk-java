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
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Builder for creating simple plugins that only need to modify configuration.
 *
 * <p>This builder provides a declarative way to create plugins for common use cases without
 * subclassing {@link PluginBase}. The resulting plugin implements both {@link
 * io.temporal.client.Plugin} and {@link io.temporal.worker.Plugin}.
 *
 * <p>Example:
 *
 * <pre>{@code
 * PluginBase myPlugin = SimplePluginBuilder.newBuilder("my-plugin")
 *     .addWorkerInterceptors(new TracingInterceptor())
 *     .addClientInterceptors(new LoggingInterceptor())
 *     .customizeClient(b -> b.setIdentity("custom-identity"))
 *     .build();
 *
 * WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
 *     .addPlugin(myPlugin)
 *     .build();
 * }</pre>
 *
 * @see PluginBase
 * @see io.temporal.client.Plugin
 * @see io.temporal.worker.Plugin
 */
@Experimental
public final class SimplePluginBuilder {

  private final String name;
  private final List<Consumer<WorkflowServiceStubsOptions.Builder>> stubsCustomizers =
      new ArrayList<>();
  private final List<Consumer<WorkflowClientOptions.Builder>> clientCustomizers = new ArrayList<>();
  private final List<Consumer<WorkerFactoryOptions.Builder>> factoryCustomizers = new ArrayList<>();
  private final List<Consumer<WorkerOptions.Builder>> workerCustomizers = new ArrayList<>();
  private final List<BiConsumer<String, Worker>> workerInitializers = new ArrayList<>();
  private final List<WorkerInterceptor> workerInterceptors = new ArrayList<>();
  private final List<WorkflowClientInterceptor> clientInterceptors = new ArrayList<>();
  private final List<ContextPropagator> contextPropagators = new ArrayList<>();

  private SimplePluginBuilder(@Nonnull String name) {
    this.name = Objects.requireNonNull(name, "Plugin name cannot be null");
  }

  /**
   * Creates a new builder with the specified plugin name.
   *
   * @param name a unique name for the plugin, used for logging and duplicate detection. Recommended
   *     format: "organization.plugin-name" (e.g., "my-org.tracing")
   * @return a new builder instance
   */
  public static SimplePluginBuilder newBuilder(@Nonnull String name) {
    return new SimplePluginBuilder(name);
  }

  /**
   * Adds a customizer for {@link WorkflowServiceStubsOptions}. Multiple customizers are applied in
   * the order they are added.
   *
   * @param customizer a consumer that modifies the options builder
   * @return this builder for chaining
   */
  public SimplePluginBuilder customizeServiceStubs(
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
  public SimplePluginBuilder customizeClient(
      @Nonnull Consumer<WorkflowClientOptions.Builder> customizer) {
    clientCustomizers.add(Objects.requireNonNull(customizer));
    return this;
  }

  /**
   * Adds a customizer for {@link WorkerFactoryOptions}. Multiple customizers are applied in the
   * order they are added.
   *
   * @param customizer a consumer that modifies the options builder
   * @return this builder for chaining
   */
  public SimplePluginBuilder customizeWorkerFactory(
      @Nonnull Consumer<WorkerFactoryOptions.Builder> customizer) {
    factoryCustomizers.add(Objects.requireNonNull(customizer));
    return this;
  }

  /**
   * Adds a customizer for {@link WorkerOptions}. Multiple customizers are applied in the order they
   * are added. The customizer is applied to all workers created by the factory.
   *
   * @param customizer a consumer that modifies the options builder
   * @return this builder for chaining
   */
  public SimplePluginBuilder customizeWorker(@Nonnull Consumer<WorkerOptions.Builder> customizer) {
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
   * SimplePluginBuilder.newBuilder("my-plugin")
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
  public SimplePluginBuilder initializeWorker(@Nonnull BiConsumer<String, Worker> initializer) {
    workerInitializers.add(Objects.requireNonNull(initializer));
    return this;
  }

  /**
   * Adds worker interceptors. Interceptors are appended to any existing interceptors in the
   * configuration.
   *
   * @param interceptors the interceptors to add
   * @return this builder for chaining
   */
  public SimplePluginBuilder addWorkerInterceptors(WorkerInterceptor... interceptors) {
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
  public SimplePluginBuilder addClientInterceptors(WorkflowClientInterceptor... interceptors) {
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
  public SimplePluginBuilder addContextPropagators(ContextPropagator... propagators) {
    contextPropagators.addAll(Arrays.asList(propagators));
    return this;
  }

  /**
   * Builds the plugin with the configured settings.
   *
   * @return a new plugin instance that implements both {@link io.temporal.client.Plugin} and {@link
   *     io.temporal.worker.Plugin}
   */
  public PluginBase build() {
    return new SimplePlugin(
        name,
        new ArrayList<>(stubsCustomizers),
        new ArrayList<>(clientCustomizers),
        new ArrayList<>(factoryCustomizers),
        new ArrayList<>(workerCustomizers),
        new ArrayList<>(workerInitializers),
        new ArrayList<>(workerInterceptors),
        new ArrayList<>(clientInterceptors),
        new ArrayList<>(contextPropagators));
  }

  /** Internal implementation of the simple plugin. */
  private static final class SimplePlugin extends PluginBase {
    private final List<Consumer<WorkflowServiceStubsOptions.Builder>> stubsCustomizers;
    private final List<Consumer<WorkflowClientOptions.Builder>> clientCustomizers;
    private final List<Consumer<WorkerFactoryOptions.Builder>> factoryCustomizers;
    private final List<Consumer<WorkerOptions.Builder>> workerCustomizers;
    private final List<BiConsumer<String, Worker>> workerInitializers;
    private final List<WorkerInterceptor> workerInterceptors;
    private final List<WorkflowClientInterceptor> clientInterceptors;
    private final List<ContextPropagator> contextPropagators;

    SimplePlugin(
        String name,
        List<Consumer<WorkflowServiceStubsOptions.Builder>> stubsCustomizers,
        List<Consumer<WorkflowClientOptions.Builder>> clientCustomizers,
        List<Consumer<WorkerFactoryOptions.Builder>> factoryCustomizers,
        List<Consumer<WorkerOptions.Builder>> workerCustomizers,
        List<BiConsumer<String, Worker>> workerInitializers,
        List<WorkerInterceptor> workerInterceptors,
        List<WorkflowClientInterceptor> clientInterceptors,
        List<ContextPropagator> contextPropagators) {
      super(name);
      this.stubsCustomizers = stubsCustomizers;
      this.clientCustomizers = clientCustomizers;
      this.factoryCustomizers = factoryCustomizers;
      this.workerCustomizers = workerCustomizers;
      this.workerInitializers = workerInitializers;
      this.workerInterceptors = workerInterceptors;
      this.clientInterceptors = clientInterceptors;
      this.contextPropagators = contextPropagators;
    }

    @Override
    @Nonnull
    public WorkflowServiceStubsOptions.Builder configureServiceStubs(
        @Nonnull WorkflowServiceStubsOptions.Builder builder) {
      for (Consumer<WorkflowServiceStubsOptions.Builder> customizer : stubsCustomizers) {
        customizer.accept(builder);
      }
      return builder;
    }

    @Override
    @Nonnull
    public WorkflowClientOptions.Builder configureClient(
        @Nonnull WorkflowClientOptions.Builder builder) {
      // Apply customizers
      for (Consumer<WorkflowClientOptions.Builder> customizer : clientCustomizers) {
        customizer.accept(builder);
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

      return builder;
    }

    @Override
    @Nonnull
    public WorkerFactoryOptions.Builder configureWorkerFactory(
        @Nonnull WorkerFactoryOptions.Builder builder) {
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

      return builder;
    }

    @Override
    @Nonnull
    public WorkerOptions.Builder configureWorker(
        @Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {
      for (Consumer<WorkerOptions.Builder> customizer : workerCustomizers) {
        customizer.accept(builder);
      }
      return builder;
    }

    @Override
    public void initializeWorker(@Nonnull String taskQueue, @Nonnull Worker worker) {
      for (BiConsumer<String, Worker> initializer : workerInitializers) {
        initializer.accept(taskQueue, worker);
      }
    }
  }
}
