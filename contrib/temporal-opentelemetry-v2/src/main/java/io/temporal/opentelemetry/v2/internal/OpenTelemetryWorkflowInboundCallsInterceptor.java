package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;

public class OpenTelemetryWorkflowInboundCallsInterceptor
    extends WorkflowInboundCallsInterceptorBase {

  private final InterceptorTracer tracer;

  public OpenTelemetryWorkflowInboundCallsInterceptor(
      InterceptorTracer tracer, WorkflowInboundCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
    super.init(new OpenTelemetryWorkflowOutboundCallsInterceptor(tracer, outboundCalls));
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    return tracer.traceInbound(
        "RunWorkflow",
        Workflow.getInfo().getWorkflowType(),
        workflowTags(),
        input.getHeader(),
        () -> super.execute(input));
  }

  @Override
  public void handleSignal(SignalInput input) {
    tracer.traceInbound(
        "HandleSignal",
        input.getSignalName(),
        workflowTags(),
        input.getHeader(),
        () -> super.handleSignal(input));
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    return tracer.traceInbound(
        "HandleQuery",
        input.getQueryName(),
        workflowTags(),
        input.getHeader(),
        () -> super.handleQuery(input));
  }

  @Override
  public void validateUpdate(UpdateInput input) {
    tracer.traceInbound(
        "ValidateUpdate",
        input.getUpdateName(),
        updateTags(),
        input.getHeader(),
        () -> super.validateUpdate(input));
  }

  @Override
  public UpdateOutput executeUpdate(UpdateInput input) {
    return tracer.traceInbound(
        "HandleUpdate",
        input.getUpdateName(),
        updateTags(),
        input.getHeader(),
        () -> super.executeUpdate(input));
  }

  private static Attributes workflowTags() {
    WorkflowInfo info = Workflow.getInfo();
    return Attributes.of(WORKFLOW_ID, info.getWorkflowId(), RUN_ID, info.getRunId());
  }

  private static Attributes updateTags() {
    AttributesBuilder tags = workflowTags().toBuilder();
    Workflow.getCurrentUpdateInfo().ifPresent(update -> tags.put(UPDATE_ID, update.getUpdateId()));
    return tags.build();
  }
}
