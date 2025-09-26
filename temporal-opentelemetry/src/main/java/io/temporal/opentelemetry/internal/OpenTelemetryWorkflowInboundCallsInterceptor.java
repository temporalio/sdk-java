package io.temporal.opentelemetry.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.internal.sync.DestroyWorkflowThreadError;
import io.temporal.opentelemetry.OpenTelemetryOptions;
import io.temporal.workflow.Workflow;

/**
 * OpenTelemetry implementation of WorkflowInboundCallsInterceptor. Mirrors the structure of
 * OpenTracingWorkflowInboundCallsInterceptor.
 */
public class OpenTelemetryWorkflowInboundCallsInterceptor
    extends WorkflowInboundCallsInterceptorBase {

  private final OpenTelemetryOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTelemetryWorkflowInboundCallsInterceptor(
      WorkflowInboundCallsInterceptor next,
      OpenTelemetryOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.contextAccessor = contextAccessor;
  }

  @Override
  public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
    super.init(
        new OpenTelemetryWorkflowOutboundCallsInterceptor(
            outboundCalls, options, spanFactory, contextAccessor));
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    Tracer tracer = options.getTracer();

    // Read the context from the header which should include both span context and baggage
    Context parentContext = contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);

    Span workflowRunSpan =
        spanFactory
            .createWorkflowRunSpan(
                tracer,
                Workflow.getInfo().getWorkflowType(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(workflowRunSpan).makeCurrent()) {
      return super.execute(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowRunSpan);
      } else {
        spanFactory.logFail(workflowRunSpan, t);
      }
      throw t;
    } finally {
      workflowRunSpan.end();
    }
  }

  @Override
  public void handleSignal(SignalInput input) {
    Tracer tracer = options.getTracer();
    Context parentContext = contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowSignalSpan =
        spanFactory
            .createWorkflowHandleSignalSpan(
                tracer,
                input.getSignalName(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(workflowSignalSpan).makeCurrent()) {
      super.handleSignal(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowSignalSpan);
      } else {
        spanFactory.logFail(workflowSignalSpan, t);
      }
      throw t;
    } finally {
      workflowSignalSpan.end();
    }
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    Tracer tracer = options.getTracer();
    Context parentContext = contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowQuerySpan =
        spanFactory
            .createWorkflowHandleQuerySpan(tracer, input.getQueryName(), parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(workflowQuerySpan).makeCurrent()) {
      return super.handleQuery(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowQuerySpan);
      } else {
        spanFactory.logFail(workflowQuerySpan, t);
      }
      throw t;
    } finally {
      workflowQuerySpan.end();
    }
  }

  @Override
  public UpdateOutput executeUpdate(UpdateInput input) {
    Tracer tracer = options.getTracer();
    Context parentContext = contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowUpdateSpan =
        spanFactory
            .createWorkflowExecuteUpdateSpan(
                tracer,
                input.getUpdateName(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(workflowUpdateSpan).makeCurrent()) {
      return super.executeUpdate(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowUpdateSpan);
      } else {
        spanFactory.logFail(workflowUpdateSpan, t);
      }
      throw t;
    } finally {
      workflowUpdateSpan.end();
    }
  }
}
