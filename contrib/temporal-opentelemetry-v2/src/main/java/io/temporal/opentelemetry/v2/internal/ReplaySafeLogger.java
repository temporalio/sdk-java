package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a logger so the records it builds are not emitted by replaying workflow code, which would
 * otherwise emit them again on every replay.
 */
public final class ReplaySafeLogger implements Logger {
  private final Logger delegate;

  public ReplaySafeLogger(Logger delegate) {
    this.delegate = delegate;
  }

  @Override
  public LogRecordBuilder logRecordBuilder() {
    return new ReplaySafeLogRecordBuilder(delegate.logRecordBuilder());
  }

  @Override
  public boolean isEnabled(Severity severity, Context context) {
    return !OpenTelemetrySuppression.shouldSuppress() && delegate.isEnabled(severity, context);
  }

  @Override
  public boolean isEnabled(Severity severity) {
    return !OpenTelemetrySuppression.shouldSuppress() && delegate.isEnabled(severity);
  }

  private static final class ReplaySafeLogRecordBuilder implements LogRecordBuilder {
    private final LogRecordBuilder delegate;

    ReplaySafeLogRecordBuilder(LogRecordBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public void emit() {
      if (OpenTelemetrySuppression.shouldSuppress()) {
        return;
      }
      delegate.emit();
    }

    @Override
    public LogRecordBuilder setTimestamp(long timestamp, TimeUnit unit) {
      delegate.setTimestamp(timestamp, unit);
      return this;
    }

    @Override
    public LogRecordBuilder setTimestamp(Instant instant) {
      delegate.setTimestamp(instant);
      return this;
    }

    @Override
    public LogRecordBuilder setObservedTimestamp(long timestamp, TimeUnit unit) {
      delegate.setObservedTimestamp(timestamp, unit);
      return this;
    }

    @Override
    public LogRecordBuilder setObservedTimestamp(Instant instant) {
      delegate.setObservedTimestamp(instant);
      return this;
    }

    @Override
    public LogRecordBuilder setContext(Context context) {
      delegate.setContext(context);
      return this;
    }

    @Override
    public LogRecordBuilder setSeverity(Severity severity) {
      delegate.setSeverity(severity);
      return this;
    }

    @Override
    public LogRecordBuilder setSeverityText(String severityText) {
      delegate.setSeverityText(severityText);
      return this;
    }

    @Override
    public LogRecordBuilder setBody(String body) {
      delegate.setBody(body);
      return this;
    }

    @Override
    public LogRecordBuilder setBody(Value<?> body) {
      delegate.setBody(body);
      return this;
    }

    @Override
    public LogRecordBuilder setAllAttributes(Attributes attributes) {
      delegate.setAllAttributes(attributes);
      return this;
    }

    @Override
    public <T> LogRecordBuilder setAttribute(AttributeKey<T> key, T value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, String value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, long value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, double value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, boolean value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, int value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setAttribute(String key, Value<?> value) {
      delegate.setAttribute(key, value);
      return this;
    }

    @Override
    public LogRecordBuilder setEventName(String eventName) {
      delegate.setEventName(eventName);
      return this;
    }

    @Override
    public LogRecordBuilder setException(Throwable throwable) {
      delegate.setException(throwable);
      return this;
    }
  }
}
