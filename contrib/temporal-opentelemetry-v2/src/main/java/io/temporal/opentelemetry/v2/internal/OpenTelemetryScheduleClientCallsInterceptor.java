package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.Attributes;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptorBase;

public class OpenTelemetryScheduleClientCallsInterceptor
    extends ScheduleClientCallsInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryScheduleClientCallsInterceptor(
      InterceptorTracer tracer, ScheduleClientCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public void createSchedule(CreateScheduleInput input) {
    if (!(input.getSchedule().getAction() instanceof ScheduleActionStartWorkflow)) {
      super.createSchedule(input);
      return;
    }

    Header header = ((ScheduleActionStartWorkflow) input.getSchedule().getAction()).getHeader();
    tracer.clearOutboundHeader(header);
    tracer.traceOutbound(
        "CreateSchedule",
        input.getId(),
        Attributes.empty(),
        header,
        () -> super.createSchedule(input));
  }

  @Override
  public void updateSchedule(UpdateScheduleInput input) {
    tracer.traceOutbound(
        "UpdateSchedule",
        input.getDescription().getId(),
        Attributes.empty(),
        () -> {
          super.updateSchedule(
              new UpdateScheduleInput(
                  input.getDescription(), updateInput -> applyUpdate(input, updateInput)));
          return null;
        });
  }

  private ScheduleUpdate applyUpdate(UpdateScheduleInput input, ScheduleUpdateInput updateInput) {
    ScheduleUpdate update = input.getUpdater().apply(updateInput);
    if (update != null && update.getSchedule().getAction() instanceof ScheduleActionStartWorkflow) {
      Header header = ((ScheduleActionStartWorkflow) update.getSchedule().getAction()).getHeader();
      tracer.clearOutboundHeader(header);
      tracer.injectOutboundHeader(header);
    }
    return update;
  }
}
