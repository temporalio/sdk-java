package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.opentelemetry.api.common.Attributes;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;

public class OpenTelemetryActivityInboundCallsInterceptor
    extends ActivityInboundCallsInterceptorBase {
  private final InterceptorTracer tracer;
  // Activity code reaches its context through Activity.getExecutionContext(), but interceptors
  // only see it in init.
  private ActivityExecutionContext context;

  public OpenTelemetryActivityInboundCallsInterceptor(
      InterceptorTracer tracer, ActivityInboundCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public void init(ActivityExecutionContext context) {
    this.context = context;
    super.init(context);
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    ActivityInfo info = context.getInfo();
    return tracer.traceInbound(
        "RunActivity",
        info.getActivityType(),
        Attributes.of(
            WORKFLOW_ID, info.getWorkflowId(),
            RUN_ID, info.getWorkflowRunId(),
            ACTIVITY_ID, info.getActivityId()),
        input.getHeader(),
        () -> super.execute(input));
  }
}
