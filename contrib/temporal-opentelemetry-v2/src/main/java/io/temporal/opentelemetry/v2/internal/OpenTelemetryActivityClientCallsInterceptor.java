package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.ACTIVITY_ID;

import io.opentelemetry.api.common.Attributes;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;

public class OpenTelemetryActivityClientCallsInterceptor
    extends ActivityClientCallsInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryActivityClientCallsInterceptor(
      InterceptorTracer tracer, ActivityClientCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input) {
    return tracer.traceOutbound(
        "StartActivity",
        input.getActivityType(),
        Attributes.of(ACTIVITY_ID, input.getOptions().getId()),
        input.getHeader(),
        () -> super.startActivity(input));
  }
}
