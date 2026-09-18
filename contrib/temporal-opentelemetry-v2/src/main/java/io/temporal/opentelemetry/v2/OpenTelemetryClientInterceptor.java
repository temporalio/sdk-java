package io.temporal.opentelemetry.v2;

import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryWorkflowClientCallsInterceptor;

public class OpenTelemetryClientInterceptor extends WorkflowClientInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryClientInterceptor(InterceptorTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next) {
    return new OpenTelemetryWorkflowClientCallsInterceptor(tracer, next);
  }
}
