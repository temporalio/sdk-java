package io.temporal.opentelemetry.v2;

import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientInterceptorBase;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryNexusClientCallsInterceptor;

public class OpenTelemetryNexusClientInterceptor extends NexusClientInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryNexusClientInterceptor(InterceptorTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public NexusClientCallsInterceptor nexusClientCallsInterceptor(NexusClientCallsInterceptor next) {
    return new OpenTelemetryNexusClientCallsInterceptor(tracer, next);
  }
}
