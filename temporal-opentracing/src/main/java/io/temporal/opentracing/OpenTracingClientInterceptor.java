package io.temporal.opentracing;

import io.temporal.common.interceptors.NexusServiceClientInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.opentracing.internal.ContextAccessor;
import io.temporal.opentracing.internal.OpenTracingNexusServiceClientInterceptor;
import io.temporal.opentracing.internal.OpenTracingWorkflowClientCallsInterceptor;
import io.temporal.opentracing.internal.SpanFactory;

public class OpenTracingClientInterceptor extends WorkflowClientInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingClientInterceptor() {
    this(OpenTracingOptions.getDefaultInstance());
  }

  public OpenTracingClientInterceptor(OpenTracingOptions options) {
    this.options = options;
    this.spanFactory = new SpanFactory(options);
    this.contextAccessor = new ContextAccessor(options);
  }

  @Override
  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next) {
    return new OpenTracingWorkflowClientCallsInterceptor(
        next, options, spanFactory, contextAccessor);
  }

  @Override
  public NexusServiceClientInterceptor nexusServiceClientInterceptor(
      NexusServiceClientInterceptor next) {
    return new OpenTracingNexusServiceClientInterceptor(
        next, options, spanFactory, contextAccessor);
  }
}
