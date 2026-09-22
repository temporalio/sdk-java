package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.context.Context;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import java.util.Collections;
import java.util.Map;

/** Copies the current OpenTelemetry context between Temporal workflow threads. */
public final class OpenTelemetryContextPropagator implements ContextPropagator {
  @Override
  public String getName() {
    return "io.temporal.opentelemetry.v2.workflow-context";
  }

  @Override
  public Map<String, Payload> serializeContext(Object context) {
    return Collections.emptyMap();
  }

  @Override
  public Object deserializeContext(Map<String, Payload> header) {
    return Context.root();
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
