package io.temporal.opentelemetry.v2.internal;

import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptorBase;

public class OpenTelemetryNexusOperationOutboundCallsInterceptor
    extends NexusOperationOutboundCallsInterceptorBase {
  public OpenTelemetryNexusOperationOutboundCallsInterceptor(
      NexusOperationOutboundCallsInterceptor next) {
    super(next);
  }
}
