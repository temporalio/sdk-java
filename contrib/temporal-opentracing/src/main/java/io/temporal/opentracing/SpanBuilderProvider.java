package io.temporal.opentracing;

import io.opentracing.Tracer;

/**
 * Fully pluggable strategy for creating OpenTracing spans based on content from the {@link
 * SpanCreationContext}
 */
public interface SpanBuilderProvider {
  Tracer.SpanBuilder createSpanBuilder(Tracer tracer, SpanCreationContext spanCreationContext);
}
