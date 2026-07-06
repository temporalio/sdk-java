package io.temporal.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Force-flushes OpenTelemetry providers without closing them. */
public final class OpenTelemetryFlushHook implements TimedShutdownHook {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryFlushHook.class);

  private final OpenTelemetry openTelemetry;
  private final Duration timeout;
  private final NanoClock clock;

  public OpenTelemetryFlushHook(OpenTelemetry openTelemetry, Duration timeout) {
    this(openTelemetry, timeout, System::nanoTime);
  }

  OpenTelemetryFlushHook(OpenTelemetry openTelemetry, Duration timeout, NanoClock clock) {
    this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void run() {
    run(timeout);
  }

  @Override
  public void run(Duration timeout) {
    long deadlineNanos = clock.nanoTime() + min(timeout, this.timeout).toNanos();
    forceFlush(tracerProvider(), deadlineNanos);
    forceFlush(meterProvider(), deadlineNanos);
  }

  interface NanoClock {
    long nanoTime();
  }

  private Object tracerProvider() {
    if (openTelemetry instanceof OpenTelemetrySdk) {
      return ((OpenTelemetrySdk) openTelemetry).getSdkTracerProvider();
    }
    return openTelemetry.getTracerProvider();
  }

  private Object meterProvider() {
    if (openTelemetry instanceof OpenTelemetrySdk) {
      return ((OpenTelemetrySdk) openTelemetry).getSdkMeterProvider();
    }
    return openTelemetry.getMeterProvider();
  }

  private void forceFlush(Object provider, long deadlineNanos) {
    if (provider == null) {
      return;
    }

    try {
      Method forceFlush = provider.getClass().getMethod("forceFlush");
      Object result = forceFlush.invoke(provider);
      join(result, remainingFlushTime(deadlineNanos));
    } catch (NoSuchMethodException e) {
      // The OpenTelemetry API no-op providers do not expose forceFlush.
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush failed provider={}", provider.getClass().getName(), e);
    }
  }

  private void join(Object result, Duration timeout) {
    if (result == null) {
      return;
    }

    try {
      Method join = result.getClass().getMethod("join", long.class, TimeUnit.class);
      join.invoke(result, timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (NoSuchMethodException e) {
      tryJoinMillis(result, timeout);
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush join failed result={}", result.getClass().getName(), e);
    }
  }

  private void tryJoinMillis(Object result, Duration timeout) {
    try {
      Method join = result.getClass().getMethod("join", long.class);
      join.invoke(result, timeout.toMillis());
    } catch (NoSuchMethodException e) {
      // Some forceFlush result implementations do not expose a blocking join method.
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
      log.warn("OpenTelemetry forceFlush join failed result={}", result.getClass().getName(), e);
    }
  }

  private static Duration requireNonNegative(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    return timeout.isNegative() ? Duration.ZERO : timeout;
  }

  private Duration remainingFlushTime(long deadlineNanos) {
    return requireNonNegative(Duration.ofNanos(deadlineNanos - clock.nanoTime()));
  }

  private static Duration min(Duration first, Duration second) {
    Duration nonNegativeFirst = requireNonNegative(first);
    Duration nonNegativeSecond = requireNonNegative(second);
    return nonNegativeFirst.compareTo(nonNegativeSecond) <= 0
        ? nonNegativeFirst
        : nonNegativeSecond;
  }
}
