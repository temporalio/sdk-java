package io.temporal.opentracing.internal;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.internal.sync.DestroyWorkflowThreadError;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.workflow.Workflow;

public class OpenTracingWorkflowInboundCallsInterceptor
    extends WorkflowInboundCallsInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final ContextAccessor contextAccessor;

  public OpenTracingWorkflowInboundCallsInterceptor(
      WorkflowInboundCallsInterceptor next,
      OpenTracingOptions options,
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
        new OpenTracingWorkflowOutboundCallsInterceptor(
            outboundCalls, options, spanFactory, contextAccessor));
  }

  @Override
  public WorkflowOutput execute(WorkflowInput input) {
    Tracer tracer = options.getTracer();
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowRunSpan =
        spanFactory
            .createWorkflowRunSpan(
                tracer,
                Workflow.getInfo().getWorkflowType(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                rootSpanContext)
            .start();
    try (Scope ignored = tracer.scopeManager().activate(workflowRunSpan)) {
      return super.execute(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowRunSpan);
      } else {
        spanFactory.logFail(workflowRunSpan, t);
      }
      throw t;
    } finally {
      workflowRunSpan.finish();
    }
  }

  @Override
  public void handleSignal(SignalInput input) {
    Tracer tracer = options.getTracer();
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowSignalSpan =
        spanFactory
            .createWorkflowHandleSignalSpan(
                tracer,
                input.getSignalName(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                rootSpanContext)
            .start();
    try (Scope ignored = tracer.scopeManager().activate(workflowSignalSpan)) {
      super.handleSignal(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowSignalSpan);
      } else {
        spanFactory.logFail(workflowSignalSpan, t);
      }
      throw t;
    } finally {
      workflowSignalSpan.finish();
    }
  }

  @Override
  public QueryOutput handleQuery(QueryInput input) {
    Tracer tracer = options.getTracer();
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowQuerySpan =
        spanFactory
            .createWorkflowHandleQuerySpan(tracer, input.getQueryName(), rootSpanContext)
            .start();
    try (Scope ignored = tracer.scopeManager().activate(workflowQuerySpan)) {
      return super.handleQuery(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowQuerySpan);
      } else {
        spanFactory.logFail(workflowQuerySpan, t);
      }
      throw t;
    } finally {
      workflowQuerySpan.finish();
    }
  }

  @Override
  public UpdateOutput executeUpdate(UpdateInput input) {
    Tracer tracer = options.getTracer();
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getHeader(), tracer);
    Span workflowSignalSpan =
        spanFactory
            .createWorkflowExecuteUpdateSpan(
                tracer,
                input.getUpdateName(),
                Workflow.getInfo().getWorkflowId(),
                Workflow.getInfo().getRunId(),
                rootSpanContext)
            .start();
    try (Scope ignored = tracer.scopeManager().activate(workflowSignalSpan)) {
      return super.executeUpdate(input);
    } catch (Throwable t) {
      if (t instanceof DestroyWorkflowThreadError) {
        spanFactory.logEviction(workflowSignalSpan);
      } else {
        spanFactory.logFail(workflowSignalSpan, t);
      }
      throw t;
    } finally {
      workflowSignalSpan.finish();
    }
  }
}
