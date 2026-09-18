package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.temporal.workflow.Workflow;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * Wraps a tracer so the spans it starts inside a workflow are replay safe. The tracer name is
 * published on the current {@link Context} while the span starts, which is where {@link
 * ReplaySafeIdGenerator} reads it.
 */
public final class ReplaySafeTracer implements Tracer {
  /** The instrumentation name of the tracer starting the current span. */
  @Nonnull static final ContextKey<String> TRACER_NAME = ContextKey.named("temporal-tracer-name");

  private final Tracer delegate;
  private final String name;

  public ReplaySafeTracer(Tracer delegate, String name) {
    this.delegate = delegate;
    this.name = name;
  }

  @Override
  public SpanBuilder spanBuilder(String spanName) {
    return new NamedStreamSpanBuilder(delegate.spanBuilder(spanName), name);
  }

  private static final class NamedStreamSpanBuilder implements SpanBuilder {
    private final SpanBuilder delegate;
    private final String tracerName;
    private boolean startTimestampSet;

    NamedStreamSpanBuilder(SpanBuilder delegate, String tracerName) {
      this.delegate = delegate;
      this.tracerName = tracerName;
    }

    @Override
    public Span startSpan() {
      if (OpenTelemetrySuppression.shouldSuppress() && !startTimestampSet) {
        delegate.setStartTimestamp(Workflow.currentTimeMillis(), TimeUnit.MILLISECONDS);
      }
      try (Scope ignored = Context.current().with(TRACER_NAME, tracerName).makeCurrent()) {
        return new ReplaySafeSpan(delegate.startSpan());
      }
    }

    @Override
    public SpanBuilder setParent(Context context) {
      delegate.setParent(context);
      return this;
    }

    @Override
    public SpanBuilder setNoParent() {
      delegate.setNoParent();
      return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext) {
      delegate.addLink(spanContext);
      return this;
    }

    @Override
    public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
      delegate.addLink(spanContext, attributes);
      return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, String value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, long value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, double value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public SpanBuilder setAttribute(String key, boolean value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public SpanBuilder setSpanKind(SpanKind spanKind) {
      delegate.setSpanKind(spanKind);
      return this;
    }

    @Override
    public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
      startTimestampSet = true;
      delegate.setStartTimestamp(startTimestamp, unit);
      return this;
    }
  }
}
