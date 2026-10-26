package io.temporal.opentelemetry.v2.internal;

import static org.junit.Assert.assertFalse;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor.CreateScheduleInput;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptorBase;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ScheduleTraceHeaderTest {
  private static final String HEADER_KEY = "_tracer-data";

  @Test
  public void staleTraceIsRemovedWhenNoSpanIsCurrent() {
    AtomicReference<Schedule> sent = new AtomicReference<>();
    OpenTelemetryScheduleClientCallsInterceptor interceptor =
        new OpenTelemetryScheduleClientCallsInterceptor(
            new InterceptorTracer(HEADER_KEY, false),
            new ScheduleClientCallsInterceptorBase(null) {
              @Override
              public void createSchedule(CreateScheduleInput input) {
                sent.set(input.getSchedule());
              }
            });
    ScheduleActionStartWorkflow action =
        ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType("Workflow")
            .setOptions(WorkflowOptions.newBuilder().setTaskQueue("queue").build())
            .setHeader(
                new Header(Collections.singletonMap(HEADER_KEY, Payload.getDefaultInstance())))
            .build();

    interceptor.createSchedule(
        new CreateScheduleInput(
            "schedule",
            Schedule.newBuilder()
                .setAction(action)
                .setSpec(ScheduleSpec.newBuilder().build())
                .build(),
            ScheduleOptions.newBuilder().build()));

    ScheduleActionStartWorkflow sentAction = (ScheduleActionStartWorkflow) sent.get().getAction();
    assertFalse(sentAction.getHeader().getValues().containsKey(HEADER_KEY));
  }
}
