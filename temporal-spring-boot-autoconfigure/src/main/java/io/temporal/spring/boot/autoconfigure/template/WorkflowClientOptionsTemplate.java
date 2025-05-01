package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import java.util.ArrayList;
import java.util.Collections;
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
  private final @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer;
  private final @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder>
      scheduleCustomizer;

  public WorkflowClientOptionsTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Nullable List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Nullable Tracer tracer,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.workflowClientInterceptors = workflowClientInterceptors;
    this.scheduleClientInterceptors = scheduleClientInterceptors;
    this.tracer = tracer;
    this.clientCustomizer = clientCustomizer;
    this.scheduleCustomizer = scheduleCustomizer;
  }

  public WorkflowClientOptions createWorkflowClientOptions() {
    WorkflowClientOptions.Builder options = WorkflowClientOptions.newBuilder();
    options.setNamespace(namespace);
    Optional.ofNullable(dataConverter).ifPresent(options::setDataConverter);

    List<WorkflowClientInterceptor> interceptors =
        new ArrayList<>(
            workflowClientInterceptors != null
                ? workflowClientInterceptors
                : Collections.EMPTY_LIST);
    if (tracer != null) {
      OpenTracingClientInterceptor openTracingClientInterceptor =
          new OpenTracingClientInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      interceptors.add(openTracingClientInterceptor);
    }

    options.setInterceptors(interceptors.stream().toArray(WorkflowClientInterceptor[]::new));

    if (clientCustomizer != null) {
      options = clientCustomizer.customize(options);
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

    if (scheduleCustomizer != null) {
      options = scheduleCustomizer.customize(options);
    }

    return options.build();
  }
}
