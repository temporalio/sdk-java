package io.temporal.opentelemetry;

import com.google.common.base.MoreObjects;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.temporal.opentelemetry.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OpenTelemetryOptions {
  private static final OpenTelemetryOptions DEFAULT_INSTANCE =
      OpenTelemetryOptions.newBuilder().build();

  private final OpenTelemetry openTelemetry;
  private final Tracer tracer;
  private final SpanBuilderProvider spanBuilderProvider;
  private final TextMapPropagator propagator;
  private final OpenTelemetrySpanContextCodec spanContextCodec;
  private final Predicate<Throwable> isErrorPredicate;

  public static OpenTelemetryOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private OpenTelemetryOptions(
      OpenTelemetry openTelemetry,
      Tracer tracer,
      SpanBuilderProvider spanBuilderProvider,
      TextMapPropagator propagator,
      OpenTelemetrySpanContextCodec spanContextCodec,
      Predicate<Throwable> isErrorPredicate) {
    if (tracer == null) throw new IllegalArgumentException("tracer shouldn't be null");
    this.openTelemetry = openTelemetry;
    this.tracer = tracer;
    this.spanBuilderProvider = spanBuilderProvider;
    this.propagator = propagator;
    this.spanContextCodec = spanContextCodec;
    this.isErrorPredicate = isErrorPredicate;
  }

  @Nonnull
  public OpenTelemetry getOpenTelemetry() {
    return openTelemetry;
  }

  @Nonnull
  public Tracer getTracer() {
    return tracer;
  }

  @Nonnull
  public SpanBuilderProvider getSpanBuilderProvider() {
    return spanBuilderProvider;
  }

  @Nonnull
  public TextMapPropagator getPropagator() {
    return propagator;
  }

  @Nonnull
  public OpenTelemetrySpanContextCodec getSpanContextCodec() {
    return spanContextCodec;
  }

  @Nonnull
  public Predicate<Throwable> getIsErrorPredicate() {
    return isErrorPredicate;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private OpenTelemetry openTelemetry;
    private Tracer tracer;
    private SpanBuilderProvider spanBuilderProvider = ActionTypeAndNameSpanBuilderProvider.INSTANCE;
    private TextMapPropagator propagator;
    private OpenTelemetrySpanContextCodec spanContextCodec =
        OpenTelemetrySpanContextCodec.TEXT_MAP_INJECT_EXTRACT_CODEC;
    private Predicate<Throwable> isErrorPredicate = t -> true;

    private Builder() {}

    public Builder setOpenTelemetry(@Nullable OpenTelemetry openTelemetry) {
      this.openTelemetry = openTelemetry;
      return this;
    }

    public Builder setTracer(@Nullable Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    /**
     * @param spanBuilderProvider custom {@link SpanBuilderProvider}, allows for more control over
     *     how OpenTelemetry spans are created, named, and given attributes.
     * @return this
     */
    public Builder setSpanBuilderProvider(@Nonnull SpanBuilderProvider spanBuilderProvider) {
      Objects.requireNonNull(spanBuilderProvider, "spanBuilderProvider can't be null");
      this.spanBuilderProvider = spanBuilderProvider;
      return this;
    }

    /**
     * @param propagator custom {@link TextMapPropagator}, allows for more control over how Context
     *     is propagated between services
     * @return this
     */
    public Builder setPropagator(@Nonnull TextMapPropagator propagator) {
      Objects.requireNonNull(propagator, "propagator can't be null");
      this.propagator = propagator;
      return this;
    }

    /**
     * @param spanContextCodec custom {@link OpenTelemetrySpanContextCodec}, allows for more control
     *     over how SpanContext is encoded and decoded from Map
     * @return this
     */
    public Builder setSpanContextCodec(@Nonnull OpenTelemetrySpanContextCodec spanContextCodec) {
      Objects.requireNonNull(spanContextCodec, "spanContextCodec can't be null");
      this.spanContextCodec = spanContextCodec;
      return this;
    }

    /**
     * @param isErrorPredicate indicates whether the received exception should cause the
     *     OpenTelemetry span to finish in an error state or not. All exceptions will be recorded to
     *     the span regardless, in order to provide a complete picture of the execution outcome. The
     *     status will be set to ERROR according to the value returned by this Predicate. By
     *     default, all exceptions will be considered errors.
     * @return this
     */
    public Builder setIsErrorPredicate(@Nonnull Predicate<Throwable> isErrorPredicate) {
      Objects.requireNonNull(isErrorPredicate, "isErrorPredicate can't be null");
      this.isErrorPredicate = isErrorPredicate;
      return this;
    }

    public OpenTelemetryOptions build() {
      OpenTelemetry otel = MoreObjects.firstNonNull(openTelemetry, OpenTelemetry.noop());
      Tracer resolvedTracer = MoreObjects.firstNonNull(tracer, otel.getTracer("io.temporal"));

      // Get the propagator from OpenTelemetry instance, ensuring both trace context and baggage are
      // propagated
      // If a custom propagator was specified, use that instead
      TextMapPropagator resolvedPropagator =
          MoreObjects.firstNonNull(propagator, otel.getPropagators().getTextMapPropagator());

      return new OpenTelemetryOptions(
          otel,
          resolvedTracer,
          spanBuilderProvider,
          resolvedPropagator,
          spanContextCodec,
          isErrorPredicate);
    }
  }
}
