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
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;

public class NonRootNamespaceTemplate extends NamespaceTemplate {

  private BeanFactory beanFactory;

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
      @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer,
      @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    super(
        namespaceProperties,
        workflowServiceStubs,
        dataConverter,
        workflowClientInterceptors,
        scheduleClientInterceptors,
        workerInterceptors,
        tracer,
        testWorkflowEnvironment,
        workerFactoryCustomizer,
        workerCustomizer,
        clientCustomizer,
        scheduleCustomizer,
        workflowImplementationCustomizer);
    this.beanFactory = beanFactory;
  }

  @Override
  public WorkersTemplate getWorkersTemplate() {
    WorkersTemplate workersTemplate = super.getWorkersTemplate();
    workersTemplate.setEnvironment(beanFactory.getBean(Environment.class));
    workersTemplate.setBeanFactory(beanFactory);

    return workersTemplate;
  }
}
