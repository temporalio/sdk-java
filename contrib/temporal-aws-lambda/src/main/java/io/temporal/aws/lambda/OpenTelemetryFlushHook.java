package io.temporal.aws.lambda;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OpenTelemetryFlushHook implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryFlushHook.class);

  private final OpenTelemetry openTelemetry;
  private final Duration timeout;

  OpenTelemetryFlushHook(OpenTelemetry openTelemetry, Duration timeout) {
    this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public void run() {
    forceFlush(openTelemetry.getTracerProvider());
    forceFlush(openTelemetry.getMeterProvider());
  }

  private void forceFlush(Object provider) {
    if (provider == null) {
      return;
    }

    try {
      Method forceFlush = provider.getClass().getMethod("forceFlush");
      Object result = forceFlush.invoke(provider);
      join(result);
    } catch (NoSuchMethodException e) {
      // The OpenTelemetry API no-op providers do not expose forceFlush.
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush failed provider={}", provider.getClass().getName(), e);
    }
  }

  private void join(Object result) {
    if (result == null) {
      return;
    }

    try {
      Method join = result.getClass().getMethod("join", long.class, TimeUnit.class);
      join.invoke(result, timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (NoSuchMethodException e) {
      tryJoinMillis(result);
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush join failed result={}", result.getClass().getName(), e);
    }
  }

  private void tryJoinMillis(Object result) {
    try {
      Method join = result.getClass().getMethod("join", long.class);
      join.invoke(result, timeout.toMillis());
    } catch (NoSuchMethodException e) {
      // Some forceFlush result implementations do not expose a blocking join method.
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush join failed result={}", result.getClass().getName(), e);
    }
  }
}
