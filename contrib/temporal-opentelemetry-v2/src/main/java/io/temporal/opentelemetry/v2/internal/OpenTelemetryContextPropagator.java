package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.context.Context;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.util.Collections;
import java.util.Map;

/**
 * Copies the current OpenTelemetry context between Temporal workflow threads. The root workflow
 * thread starts from the start header, so the workflow constructor already sees the caller's span.
 */
public final class OpenTelemetryContextPropagator implements ContextPropagator {
  private final SpanCodec codec;

  public OpenTelemetryContextPropagator(String headerKey) {
    this.codec = new SpanCodec(headerKey);
  }

  @Override
  public String getName() {
    return "io.temporal.opentelemetry.v2.workflow-context";
  }

  @Override
  public Map<String, Payload> serializeContext(Object context) {
    if (!(context instanceof Context)) {
      return Collections.emptyMap();
    }
    return codec.serialize((Context) context);
  }

  @Override
  public Object deserializeContext(Map<String, Payload> header) {
    return codec.readFromRoot(header);
  }

  @Override
  public Object getCurrentContext() {
    return Context.current();
  }

  @Override
  public void setCurrentContext(Object context) {
    TemporalContextStorage.setWorkflowContext((Context) context);
  }
}
