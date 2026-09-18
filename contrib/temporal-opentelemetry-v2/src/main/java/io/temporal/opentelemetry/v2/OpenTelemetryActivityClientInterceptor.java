package io.temporal.opentelemetry.v2;

import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientInterceptorBase;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryActivityClientCallsInterceptor;

public class OpenTelemetryActivityClientInterceptor extends ActivityClientInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryActivityClientInterceptor(InterceptorTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return new OpenTelemetryActivityClientCallsInterceptor(tracer, next);
  }
}
