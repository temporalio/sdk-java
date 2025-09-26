package io.temporal.opentelemetry;

import io.nexusrpc.handler.OperationContext;
import io.temporal.common.interceptors.*;
import io.temporal.opentelemetry.internal.*;

public class OpenTelemetryWorkerInterceptor implements WorkerInterceptor {
  private final OpenTelemetryOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTelemetryWorkerInterceptor() {
    this(OpenTelemetryOptions.getDefaultInstance());
  }

  public OpenTelemetryWorkerInterceptor(OpenTelemetryOptions options) {
    this.options = options;
    this.spanFactory = new SpanFactory(options);
    this.contextAccessor = new ContextAccessor(options);
  }

  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return new OpenTelemetryWorkflowInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return new OpenTelemetryActivityInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }

  @Override
  public NexusOperationInboundCallsInterceptor interceptNexusOperation(
      OperationContext context, NexusOperationInboundCallsInterceptor next) {
    return new OpenTelemetryNexusOperationInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }
}
