package io.temporal.opentracing.internal;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.SpanOperationType;

public class OpenTracingWorkflowClientCallsInterceptor extends WorkflowClientCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingWorkflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public WorkflowStartOutput start(WorkflowStartInput input) {
    Span workflowStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () -> createWorkflowStartSpanBuilder(input, SpanOperationType.START_WORKFLOW).start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.start(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  @Override
  public WorkflowSignalOutput signal(WorkflowSignalInput input) {
    Span workflowSignalSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowSignalSpan(
                        tracer,
                        input.getSignalName(),
                        input.getWorkflowExecution().getWorkflowId(),
                        input.getWorkflowExecution().getRunId())
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowSignalSpan)) {
      return super.signal(input);
    } finally {
      workflowSignalSpan.finish();
    }
  }

  @Override
  public WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input) {
    WorkflowStartInput workflowStartInput = input.getWorkflowStartInput();
    Span workflowStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                createWorkflowStartSpanBuilder(
                        workflowStartInput, SpanOperationType.SIGNAL_WITH_START_WORKFLOW)
                    .start(),
            workflowStartInput.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.signalWithStart(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  @Override
  public WorkflowUpdateWithStartOutput updateWithStart(WorkflowUpdateWithStartInput input) {
    WorkflowStartInput workflowStartInput = input.getWorkflowStartInput();
    Span workflowStartSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                createWorkflowStartSpanBuilder(
                        workflowStartInput, SpanOperationType.UPDATE_WITH_START_WORKFLOW)
                    .start(),
            workflowStartInput.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartSpan)) {
      return super.updateWithStart(input);
    } finally {
      workflowStartSpan.finish();
    }
  }

  @Override
  public <R> QueryOutput<R> query(QueryInput<R> input) {
    Span workflowQuerySpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowQuerySpan(
                        tracer,
                        input.getQueryType(),
                        input.getWorkflowExecution().getWorkflowId(),
                        input.getWorkflowExecution().getRunId())
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowQuerySpan)) {
      return super.query(input);
    } finally {
      workflowQuerySpan.finish();
    }
  }

  @Override
  public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
    Span workflowStartUpdateSpan =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createWorkflowStartUpdateSpan(
                        tracer,
                        input.getUpdateName(),
                        input.getWorkflowExecution().getWorkflowId(),
                        input.getWorkflowExecution().getRunId())
                    .start(),
            input.getHeader(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(workflowStartUpdateSpan)) {
      return super.startUpdate(input);
    } finally {
      workflowStartUpdateSpan.finish();
    }
  }

  private Tracer.SpanBuilder createWorkflowStartSpanBuilder(
      WorkflowStartInput input, SpanOperationType operationType) {
    return spanFactory.createWorkflowStartSpan(
        tracer, operationType, input.getWorkflowType(), input.getWorkflowId());
  }
}
