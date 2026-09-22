package io.temporal.opentelemetry.v2;

import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.common.interceptors.ScheduleClientInterceptorBase;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryScheduleClientCallsInterceptor;

public class OpenTelemetryScheduleClientInterceptor extends ScheduleClientInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryScheduleClientInterceptor(InterceptorTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public ScheduleClientCallsInterceptor scheduleClientCallsInterceptor(
      ScheduleClientCallsInterceptor next) {
    return new OpenTelemetryScheduleClientCallsInterceptor(tracer, next);
  }
}
