package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NamespaceTemplate {
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final @Nonnull WorkflowServiceStubs workflowServiceStubs;
  private final @Nullable DataConverter dataConverter;
  private final @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors;
  private final @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors;
  private final @Nullable List<WorkerInterceptor> workerInterceptors;
  private final @Nullable Tracer tracer;
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private final @Nullable List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>>
      workerFactoryCustomizer;
  private final @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizer;
  private final @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>>
      clientCustomizer;
  private final @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>>
      scheduleCustomizer;
  private final @Nullable List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
      workflowImplementationCustomizer;

  private ClientTemplate clientTemplate;
  private WorkersTemplate workersTemplate;

  public NamespaceTemplate(
      @Nonnull NamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable
          List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> workerFactoryCustomizer,
      @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizer,
      @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizer,
      @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizer,
      @Nullable
          List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
              workflowImplementationCustomizer) {
    this.namespaceProperties = namespaceProperties;
    this.workflowServiceStubs = workflowServiceStubs;
    this.dataConverter = dataConverter;
    this.workflowClientInterceptors = workflowClientInterceptors;
    this.scheduleClientInterceptors = scheduleClientInterceptors;
    this.workerInterceptors = workerInterceptors;
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;

    this.workerFactoryCustomizer = workerFactoryCustomizer;
    this.workerCustomizer = workerCustomizer;
    this.clientCustomizer = clientCustomizer;
    this.scheduleCustomizer = scheduleCustomizer;
    this.workflowImplementationCustomizer = workflowImplementationCustomizer;
  }

  public ClientTemplate getClientTemplate() {
    if (clientTemplate == null) {
      this.clientTemplate =
          new ClientTemplate(
              namespaceProperties.getNamespace(),
              dataConverter,
              workflowClientInterceptors,
              scheduleClientInterceptors,
              tracer,
              workflowServiceStubs,
              testWorkflowEnvironment,
              clientCustomizer,
              scheduleCustomizer);
    }
    return clientTemplate;
  }

  public WorkersTemplate getWorkersTemplate() {
    if (workersTemplate == null) {
      this.workersTemplate =
          new WorkersTemplate(
              namespaceProperties,
              getClientTemplate(),
              workerInterceptors,
              tracer,
              testWorkflowEnvironment,
              workerFactoryCustomizer,
              workerCustomizer,
              workflowImplementationCustomizer);
    }
    return this.workersTemplate;
  }
}
