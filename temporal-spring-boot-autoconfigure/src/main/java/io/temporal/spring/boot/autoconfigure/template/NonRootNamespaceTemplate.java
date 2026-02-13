package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowClientPlugin;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkerPlugin;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;

public class NonRootNamespaceTemplate extends NamespaceTemplate {

  private final BeanFactory beanFactory;

  public NonRootNamespaceTemplate(
      @Nonnull BeanFactory beanFactory,
      @Nonnull NonRootNamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable
          List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> workerFactoryCustomizers,
      @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizers,
      @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizers,
      @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizers,
      @Nullable
          List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
              workflowImplementationCustomizers,
      @Nullable List<WorkflowClientPlugin> workflowClientPlugins,
      @Nullable List<ScheduleClientPlugin> scheduleClientPlugins,
      @Nullable List<WorkerPlugin> workerPlugins) {
    super(
        namespaceProperties,
        workflowServiceStubs,
        dataConverter,
        workflowClientInterceptors,
        scheduleClientInterceptors,
        workerInterceptors,
        tracer,
        testWorkflowEnvironment,
        workerFactoryCustomizers,
        workerCustomizers,
        clientCustomizers,
        scheduleCustomizers,
        workflowImplementationCustomizers,
        workflowClientPlugins,
        scheduleClientPlugins,
        workerPlugins);
    this.beanFactory = beanFactory;
  }

  /**
   * @deprecated Use constructor with plugins parameters
   */
  @Deprecated
  public NonRootNamespaceTemplate(
      @Nonnull BeanFactory beanFactory,
      @Nonnull NonRootNamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable List<WorkerInterceptor> workerInterceptors,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable
          List<TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>> workerFactoryCustomizers,
      @Nullable List<TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizers,
      @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizers,
      @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizers,
      @Nullable
          List<TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
              workflowImplementationCustomizers) {
    this(
        beanFactory,
        namespaceProperties,
        workflowServiceStubs,
        dataConverter,
        workflowClientInterceptors,
        scheduleClientInterceptors,
        workerInterceptors,
        tracer,
        testWorkflowEnvironment,
        workerFactoryCustomizers,
        workerCustomizers,
        clientCustomizers,
        scheduleCustomizers,
        workflowImplementationCustomizers,
        null,
        null,
        null);
  }

  @Override
  public WorkersTemplate getWorkersTemplate() {
    WorkersTemplate workersTemplate = super.getWorkersTemplate();
    workersTemplate.setEnvironment(beanFactory.getBean(Environment.class));
    workersTemplate.setBeanFactory(beanFactory);

    return workersTemplate;
  }
}
