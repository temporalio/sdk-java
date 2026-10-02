package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.Attributes;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.client.schedules.ScheduleUpdateInput;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptorBase;
import java.util.HashMap;

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

    tracer.traceOutbound(
        "CreateSchedule",
        input.getId(),
        Attributes.empty(),
        () -> {
          super.createSchedule(
              new CreateScheduleInput(
                  input.getId(), withTraceHeader(input.getSchedule()), input.getOptions()));
          return null;
        });
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
    if (update == null
        || !(update.getSchedule().getAction() instanceof ScheduleActionStartWorkflow)) {
      return update;
    }
    return new ScheduleUpdate(
        withTraceHeader(update.getSchedule()), update.getTypedSearchAttributes());
  }

  /**
   * Writes the current trace into a copy of the action header, so the caller's header, which may be
   * immutable or reused, is never modified.
   */
  private Schedule withTraceHeader(Schedule schedule) {
    ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) schedule.getAction();
    Header header = new Header(new HashMap<>(action.getHeader().getValues()));
    tracer.injectOutboundHeader(header);
    return Schedule.newBuilder(schedule)
        .setAction(ScheduleActionStartWorkflow.newBuilder(action).setHeader(header).build())
        .build();
  }
}
