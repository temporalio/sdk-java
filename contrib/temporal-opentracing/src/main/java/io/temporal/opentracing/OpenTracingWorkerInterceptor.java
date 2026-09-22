package io.temporal.opentracing;

import io.nexusrpc.handler.OperationContext;
import io.temporal.common.interceptors.*;
import io.temporal.opentracing.internal.*;

public class OpenTracingWorkerInterceptor implements WorkerInterceptor {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingWorkerInterceptor() {
    this(OpenTracingOptions.getDefaultInstance());
  }

  public OpenTracingWorkerInterceptor(OpenTracingOptions options) {
    this.options = options;
    this.spanFactory = new SpanFactory(options);
    this.contextAccessor = new ContextAccessor(options);
  }

  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return new OpenTracingWorkflowInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return new OpenTracingActivityInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }

  @Override
  public NexusOperationInboundCallsInterceptor interceptNexusOperation(
      OperationContext context, NexusOperationInboundCallsInterceptor next) {
    return new OpenTracingNexusOperationInboundCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }
}
