package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationContext;
import io.opentelemetry.api.common.Attributes;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptorBase;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;

public class OpenTelemetryNexusOperationInboundCallsInterceptor
    extends NexusOperationInboundCallsInterceptorBase {

  private final InterceptorTracer tracer;

  public OpenTelemetryNexusOperationInboundCallsInterceptor(
      InterceptorTracer tracer, NexusOperationInboundCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public void init(NexusOperationOutboundCallsInterceptor outboundCalls) {
    super.init(new OpenTelemetryNexusOperationOutboundCallsInterceptor(outboundCalls));
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    OperationContext context = input.getOperationContext();
    return tracer.traceNexusInbound(
        "RunStartNexusOperationHandler",
        spanName(context),
        nexusTags(context),
        context.getHeaders(),
        () -> super.startOperation(input));
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    OperationContext context = input.getOperationContext();
    return tracer.traceNexusInbound(
        "RunCancelNexusOperationHandler",
        spanName(context),
        nexusTags(context),
        context.getHeaders(),
        () -> super.cancelOperation(input));
  }

  private static String spanName(OperationContext context) {
    return context.getService() + "/" + context.getOperation();
  }

  private static Attributes nexusTags(OperationContext context) {
    return Attributes.of(
        NEXUS_SERVICE, context.getService(), NEXUS_OPERATION, context.getOperation());
  }
}
