package io.temporal.opentelemetry.internal;

import io.nexusrpc.OperationException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptorBase;
import io.temporal.opentelemetry.OpenTelemetryOptions;

public class OpenTelemetryNexusOperationInboundCallsInterceptor
    extends NexusOperationInboundCallsInterceptorBase {

  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTelemetryNexusOperationInboundCallsInterceptor(
      NexusOperationInboundCallsInterceptor next,
      OpenTelemetryOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    Context parentContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationStartSpan =
        spanFactory
            .createStartNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(operationStartSpan).makeCurrent()) {
      return super.startOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationStartSpan, t);
      throw t;
    } finally {
      operationStartSpan.end();
    }
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    Context parentContext =
        contextAccessor.readSpanContextFromHeader(input.getOperationContext().getHeaders(), tracer);

    Span operationCancelSpan =
        spanFactory
            .createCancelNexusOperationSpan(
                tracer,
                input.getOperationContext().getService(),
                input.getOperationContext().getOperation(),
                parentContext)
            .startSpan();

    try (Scope ignored = parentContext.with(operationCancelSpan).makeCurrent()) {
      return super.cancelOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(operationCancelSpan, t);
      throw t;
    } finally {
      operationCancelSpan.end();
    }
  }
}
