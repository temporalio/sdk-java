package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowClientPlugin;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorkflowClientOptionsTemplate {
  private final @Nonnull String namespace;
  private final @Nullable DataConverter dataConverter;
  private final @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors;
  private final @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors;
  private final @Nullable Tracer tracer;
  private final @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>>
      clientCustomizers;
  private final @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>>
      scheduleCustomizers;
  private final @Nullable List<WorkflowClientPlugin> workflowClientPlugins;
  private final @Nullable List<ScheduleClientPlugin> scheduleClientPlugins;

  public WorkflowClientOptionsTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable Tracer tracer,
      @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizers,
      @Nullable List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizers,
      @Nullable List<WorkflowClientPlugin> workflowClientPlugins,
      @Nullable List<ScheduleClientPlugin> scheduleClientPlugins) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.workflowClientInterceptors = workflowClientInterceptors;
    this.scheduleClientInterceptors = scheduleClientInterceptors;
    this.tracer = tracer;
    this.clientCustomizers = clientCustomizers;
    this.scheduleCustomizers = scheduleCustomizers;
    this.workflowClientPlugins = workflowClientPlugins;
    this.scheduleClientPlugins = scheduleClientPlugins;
  }

  /**
   * @deprecated Use constructor with plugins parameters
   */
  @Deprecated
  public WorkflowClientOptionsTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable Tracer tracer,
      @Nullable List<TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizers,
      @Nullable
          List<TemporalOptionsCustomizer<ScheduleClientOptions.Builder>> scheduleCustomizers) {
    this(
        namespace,
        dataConverter,
        workflowClientInterceptors,
        scheduleClientInterceptors,
        tracer,
        clientCustomizers,
        scheduleCustomizers,
        null,
        null);
  }

  public WorkflowClientOptions createWorkflowClientOptions() {
    WorkflowClientOptions.Builder options = WorkflowClientOptions.newBuilder();
    options.setNamespace(namespace);
    Optional.ofNullable(dataConverter).ifPresent(options::setDataConverter);

    List<WorkflowClientInterceptor> interceptors = new ArrayList<>();
    if (tracer != null) {
      OpenTracingClientInterceptor openTracingClientInterceptor =
          new OpenTracingClientInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      interceptors.add(openTracingClientInterceptor);
    }
    if (workflowClientInterceptors != null) {
      interceptors.addAll(workflowClientInterceptors);
    }

    options.setInterceptors(interceptors.toArray(new WorkflowClientInterceptor[0]));

    if (workflowClientPlugins != null && !workflowClientPlugins.isEmpty()) {
      options.setPlugins(workflowClientPlugins.toArray(new WorkflowClientPlugin[0]));
    }

    if (clientCustomizers != null) {
      for (TemporalOptionsCustomizer<WorkflowClientOptions.Builder> customizer :
          clientCustomizers) {
        options = customizer.customize(options);
      }
    }
    return options.build();
  }

  public ScheduleClientOptions createScheduleClientOptions() {
    ScheduleClientOptions.Builder options = ScheduleClientOptions.newBuilder();
    options.setNamespace(namespace);
    Optional.ofNullable(dataConverter).ifPresent(options::setDataConverter);
    if (scheduleClientInterceptors != null && !scheduleClientInterceptors.isEmpty()) {
      options.setInterceptors(scheduleClientInterceptors);
    }

    if (scheduleClientPlugins != null && !scheduleClientPlugins.isEmpty()) {
      options.setPlugins(scheduleClientPlugins.toArray(new ScheduleClientPlugin[0]));
    }

    if (scheduleCustomizers != null) {
      for (TemporalOptionsCustomizer<ScheduleClientOptions.Builder> customizer :
          scheduleCustomizers) {
        options = customizer.customize(options);
      }
    }

    return options.build();
  }
}
