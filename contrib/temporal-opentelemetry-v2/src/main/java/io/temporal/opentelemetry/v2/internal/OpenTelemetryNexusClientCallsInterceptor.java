package io.temporal.opentelemetry.v2.internal;

import static io.temporal.opentelemetry.v2.internal.TagKeys.*;

import io.opentelemetry.api.common.Attributes;
import io.temporal.common.interceptors.NexusClientCallsInterceptor;
import io.temporal.common.interceptors.NexusClientCallsInterceptorBase;

public class OpenTelemetryNexusClientCallsInterceptor extends NexusClientCallsInterceptorBase {
  private final InterceptorTracer tracer;

  public OpenTelemetryNexusClientCallsInterceptor(
      InterceptorTracer tracer, NexusClientCallsInterceptor next) {
    super(next);
    this.tracer = tracer;
  }

  @Override
  public StartNexusOperationExecutionOutput startNexusOperationExecution(
      StartNexusOperationExecutionInput input) {
    return tracer.traceNexusOutbound(
        "StartNexusOperation",
        input.getService() + "/" + input.getOperation(),
        Attributes.of(
            NEXUS_ENDPOINT, input.getEndpoint(),
            NEXUS_SERVICE, input.getService(),
            NEXUS_OPERATION, input.getOperation()),
        input.getHeaders(),
        () -> super.startNexusOperationExecution(input));
  }
}
