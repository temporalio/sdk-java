package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.concurrent.TimeUnit;

/** Wraps a span so that replayed code does not end it, which would export a duplicate. */
public final class ReplaySafeSpan implements Span {
  private final Span delegate;

  public ReplaySafeSpan(Span delegate) {
    this.delegate = delegate;
  }

  @Override
  public void end() {
    if (WorkflowUnsafe.isWorkflowThread()
        && WorkflowUnsafe.isSubjectToReplay()
        && WorkflowUnsafe.isReplaying()) {
      return;
    }
    delegate.end();
  }

  @Override
  public void end(long timestamp, TimeUnit unit) {
    if (WorkflowUnsafe.isWorkflowThread()
        && WorkflowUnsafe.isSubjectToReplay()
        && WorkflowUnsafe.isReplaying()) {
      return;
    }
    delegate.end(timestamp, unit);
  }

  @Override
  public <T> Span setAttribute(AttributeKey<T> key, T value) {
    delegate.setAttribute(key, value);
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes) {
    delegate.addEvent(name, attributes);
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    delegate.addEvent(name, attributes, timestamp, unit);
    return this;
  }

  @Override
  public Span setStatus(StatusCode statusCode, String description) {
    delegate.setStatus(statusCode, description);
    return this;
  }

  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    delegate.recordException(exception, additionalAttributes);
    return this;
  }

  @Override
  public Span updateName(String name) {
    delegate.updateName(name);
    return this;
  }

  @Override
  public SpanContext getSpanContext() {
    return delegate.getSpanContext();
  }

  @Override
  public boolean isRecording() {
    return delegate.isRecording();
  }
}
