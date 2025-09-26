package io.temporal.opentelemetry.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.opentelemetry.OpenTelemetryOptions;

public class OpenTelemetryActivityInboundCallsInterceptor
    extends ActivityInboundCallsInterceptorBase {

  private final OpenTelemetryOptions options;
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  private ActivityExecutionContext activityExecutionContext;

  public OpenTelemetryActivityInboundCallsInterceptor(
      ActivityInboundCallsInterceptor next,
      OpenTelemetryOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public void init(ActivityExecutionContext context) {
    // Workflow Interceptors have access to Workflow.getInfo methods,
    // but Activity Interceptors don't have access to Activity.getExecutionContext().getInfo()
    // This is inconsistent and should be addressed, but this is a workaround.
    this.activityExecutionContext = context;
    super.init(context);
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    // Extract the parent context including both span context and baggage
    Context parentContext = contextAccessor.readSpanContextFromHeader(input.getHeader());
    ActivityInfo activityInfo = activityExecutionContext.getInfo();

    // Create the activity span with the parent context
    Span activityRunSpan =
        spanFactory
            .createActivityRunSpan(
                tracer,
                activityInfo.getActivityType(),
                activityInfo.getWorkflowId(),
                activityInfo.getRunId(),
                parentContext)
            .startSpan();

    // Make sure this context is passed through properly
    try (Scope ignored = parentContext.with(activityRunSpan).makeCurrent()) {
      return super.execute(input);
    } catch (Throwable t) {
      spanFactory.logFail(activityRunSpan, t);
      throw t;
    } finally {
      activityRunSpan.end();
    }
  }
}
