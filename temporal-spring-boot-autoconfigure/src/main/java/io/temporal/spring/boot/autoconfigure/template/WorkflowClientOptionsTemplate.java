package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorkflowClientOptionsTemplate {
  private final @Nonnull String namespace;
  private final @Nullable DataConverter dataConverter;
  private final @Nullable Tracer tracer;
  private final @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer;
  private final @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder>
      scheduleCustomizer;

  public WorkflowClientOptionsTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable Tracer tracer,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer) {
    this.namespace = namespace;
    this.dataConverter = dataConverter;
    this.tracer = tracer;
    this.clientCustomizer = clientCustomizer;
    this.scheduleCustomizer = scheduleCustomizer;
  }

  public WorkflowClientOptions createWorkflowClientOptions() {
    WorkflowClientOptions.Builder options = WorkflowClientOptions.newBuilder();
    options.setNamespace(namespace);
    Optional.ofNullable(dataConverter).ifPresent(options::setDataConverter);

    if (tracer != null) {
      OpenTracingClientInterceptor openTracingClientInterceptor =
          new OpenTracingClientInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      options.setInterceptors(openTracingClientInterceptor);
    }

    if (clientCustomizer != null) {
      options = clientCustomizer.customize(options);
    }

    return options.build();
  }

  public ScheduleClientOptions createScheduleClientOptions() {
    ScheduleClientOptions.Builder options = ScheduleClientOptions.newBuilder();
    options.setNamespace(namespace);
    Optional.ofNullable(dataConverter).ifPresent(options::setDataConverter);

    if (scheduleCustomizer != null) {
      options = scheduleCustomizer.customize(options);
    }

    return options.build();
  }
}
