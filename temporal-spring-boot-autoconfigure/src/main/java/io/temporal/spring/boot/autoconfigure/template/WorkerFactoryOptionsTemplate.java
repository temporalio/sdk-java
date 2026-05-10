package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorkerFactoryOptionsTemplate {
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final @Nullable List<WorkerInterceptor> workerInterceptors;
  private final @Nullable Tracer tracer;
  private final @Nullable List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> customizer;
  private final @Nullable List<WorkerPlugin> plugins;

  public WorkerFactoryOptionsTemplate(
      @Nonnull NamespaceProperties namespaceProperties,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> customizer,
      @Nullable List<WorkerPlugin> plugins) {
    this.namespaceProperties = namespaceProperties;
    this.workerInterceptors = workerInterceptors;
    this.tracer = tracer;
    this.customizer = customizer;
    this.plugins = plugins;
  }

  /**
   * @deprecated Use constructor with plugins parameter
   */
  @Deprecated
  public WorkerFactoryOptionsTemplate(
      @Nonnull NamespaceProperties namespaceProperties,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> customizer) {
    this(namespaceProperties, workerInterceptors, tracer, customizer, null);
  }

  public WorkerFactoryOptions createWorkerFactoryOptions() {
    WorkerFactoryOptions.Builder options = WorkerFactoryOptions.newBuilder();

    @Nullable
    NamespaceProperties.WorkflowCacheProperties workflowCache =
        namespaceProperties.getWorkflowCache();
    if (workflowCache != null) {
      Optional.ofNullable(workflowCache.getMaxInstances()).ifPresent(options::setWorkflowCacheSize);
      Optional.ofNullable(workflowCache.getMaxThreads())
          .ifPresent(options::setMaxWorkflowThreadCount);
      Optional.ofNullable(workflowCache.isUsingVirtualWorkflowThreads())
          .ifPresent(options::setUsingVirtualWorkflowThreads);
    }

    List<WorkerInterceptor> interceptors = new ArrayList<>();
    if (tracer != null) {
      OpenTracingWorkerInterceptor openTracingClientInterceptor =
          new OpenTracingWorkerInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      interceptors.add(openTracingClientInterceptor);
    }
    if (workerInterceptors != null) {
      interceptors.addAll(workerInterceptors);
    }
    options.setWorkerInterceptors(interceptors.toArray(new WorkerInterceptor[0]));

    if (plugins != null && !plugins.isEmpty()) {
      options.setPlugins(plugins.toArray(new WorkerPlugin[0]));
    }

    if (customizer != null) {
      for (TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> customizer : customizer) {
        options = customizer.customize(options);
      }
    }
    return options.build();
  }
}
