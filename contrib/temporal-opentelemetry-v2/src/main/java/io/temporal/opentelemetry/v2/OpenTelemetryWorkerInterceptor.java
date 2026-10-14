package io.temporal.opentelemetry.v2;

import io.nexusrpc.handler.OperationContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryActivityInboundCallsInterceptor;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryNexusOperationInboundCallsInterceptor;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryWorkflowInboundCallsInterceptor;

public class OpenTelemetryWorkerInterceptor implements WorkerInterceptor {
  private final InterceptorTracer tracer;

  public OpenTelemetryWorkerInterceptor(InterceptorTracer tracer) {
    this.tracer = tracer;
  }

  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return new OpenTelemetryWorkflowInboundCallsInterceptor(tracer, next);
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return new OpenTelemetryActivityInboundCallsInterceptor(tracer, next);
  }

  @Override
  public NexusOperationInboundCallsInterceptor interceptNexusOperation(
      OperationContext context, NexusOperationInboundCallsInterceptor next) {
    return new OpenTelemetryNexusOperationInboundCallsInterceptor(tracer, next);
  }
}
