package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import java.util.Arrays;

public class OpenTelemetryWorkflowClientCallsInterceptor
    extends WorkflowClientCallsInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryWorkflowClientCallsInterceptor(
      InterceptorTracer tracer, WorkflowClientCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    return tracer.traceOutbound(
        "StartWorkflow",
        input.getWorkflowType(),
        Attributes.of(WORKFLOW_ID, input.getWorkflowId()),
        input.getHeader(),
        () -> super.start(input));
  }

  @Override
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    return tracer.traceOutbound(
        "SignalWorkflow",
        input.getSignalName(),
        workflowExecutionTags(input.getWorkflowExecution()),
        input.getHeader(),
        () -> super.signal(input));
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    WorkflowStartInput start = input.getWorkflowStartInput();
    return tracer.traceOutbound(
        "SignalWithStartWorkflow",
        start.getWorkflowType(),
        Attributes.of(WORKFLOW_ID, start.getWorkflowId()),
        start.getHeader(),
        () -> super.signalWithStart(input));
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    return tracer.traceOutbound(
        "QueryWorkflow",
        input.getQueryType(),
        workflowExecutionTags(input.getWorkflowExecution()),
        input.getHeader(),
        () -> super.query(input));
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
    AttributesBuilder attributes = workflowExecutionTags(input.getWorkflowExecution()).toBuilder();
    attributes.put(UPDATE_ID, input.getUpdateId());
    return tracer.traceOutbound(
        "StartWorkflowUpdate",
        input.getUpdateName(),
        attributes.build(),
        input.getHeader(),
        () -> super.startUpdate(input));
  }

  @Override
  public CancelOutput cancel(CancelInput input) {
    return tracer.traceOutbound(
        "CancelWorkflow",
        "",
        workflowExecutionTags(input.getWorkflowExecution()),
        () -> super.cancel(input));
  }

  @Override
  public TerminateOutput terminate(TerminateInput input) {
    AttributesBuilder attributes = workflowExecutionTags(input.getWorkflowExecution()).toBuilder();
    if (input.getReason() != null) {
      attributes.put(TERMINATE_REASON, input.getReason());
    }
    return tracer.traceOutbound(
        "TerminateWorkflow", "", attributes.build(), () -> super.terminate(input));
  }

  @Override
  public DescribeWorkflowOutput describe(DescribeWorkflowInput input) {
    return tracer.traceOutbound(
        "DescribeWorkflow",
        "",
        workflowExecutionTags(input.getWorkflowExecution()),
        () -> super.describe(input));
  }

  @Override
  public <R> WorkflowUpdateWithStartOutput<R> updateWithStart(
      WorkflowUpdateWithStartInput<R> input) {
    WorkflowStartInput start = input.getWorkflowStartInput();
    StartUpdateInput<R> update = input.getStartUpdateInput();
    // The start header reaches the workflow and the update header reaches the update handler.
    return tracer.traceOutbound(
        "UpdateWithStartWorkflow",
        update.getUpdateName(),
        Attributes.of(WORKFLOW_ID, start.getWorkflowId(), UPDATE_ID, update.getUpdateId()),
        Arrays.asList(start.getHeader(), update.getHeader()),
        () -> super.updateWithStart(input));
  }

  private static Attributes workflowExecutionTags(
      io.temporal.api.common.v1.WorkflowExecution execution) {
    return Attributes.of(WORKFLOW_ID, execution.getWorkflowId(), RUN_ID, execution.getRunId());
  }
}
