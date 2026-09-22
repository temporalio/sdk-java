package io.temporal.opentracing.internal;

import io.nexusrpc.OperationException;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;

public class OpenTracingNexusOperationInboundCallsInterceptor
    extends NexusOperationInboundCallsInterceptorBase {
  private final OpenTracingOptions options;
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingNexusOperationInboundCallsInterceptor(
      NexusOperationInboundCallsInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.options = options;
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationStartSpan =
        spanFactory
            .createStartNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(operationStartSpan)) {
      return super.startOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationStartSpan, t);
      throw t;
    } finally {
      operationStartSpan.finish();
    }
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    SpanContext rootSpanContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationCancelSpan =
        spanFactory
            .createCancelNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                rootSpanContext)
            .start();
    try (Scope scope = tracer.scopeManager().activate(operationCancelSpan)) {
      return super.cancelOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationCancelSpan, t);
      throw t;
    } finally {
      operationCancelSpan.finish();
    }
  }
}
