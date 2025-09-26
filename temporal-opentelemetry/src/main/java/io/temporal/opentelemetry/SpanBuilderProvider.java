package io.temporal.opentelemetry;

import io.opentelemetry.api.trace.Tracer;

/**
 * Fully pluggable strategy for creating OpenTracing spans based on content from the {@link
 * SpanCreationContext}
 */
public interface SpanBuilderProvider {
  io.opentelemetry.api.trace.SpanBuilder createSpanBuilder(
      Tracer tracer, SpanCreationContext spanCreationContext);
}
