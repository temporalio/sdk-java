package io.temporal.opentelemetry.v2;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.temporal.common.Experimental;
import io.temporal.opentelemetry.v2.internal.ReplaySafeIdGenerator;
import io.temporal.opentelemetry.v2.internal.ReplaySafeLogger;
import io.temporal.opentelemetry.v2.internal.ReplaySafeMeter;
import io.temporal.opentelemetry.v2.internal.ReplaySafeTracer;
import java.io.Closeable;
import javax.annotation.Nonnull;

/**
 * The {@link OpenTelemetry} to use for OpenTelemetry integration with Temporal. Register it with
 * {@code GlobalOpenTelemetry.set}; tracers, meters, and loggers obtained from it are replay safe
 * inside workflows.
 */
@Experimental
public final class ReplaySafeOpenTelemetry implements OpenTelemetry, Closeable {
  private final ReplaySafeTracerProvider tracerProvider;
  private final ReplaySafeMeterProvider meterProvider;
  private final ReplaySafeLoggerProvider loggerProvider;
  private final ContextPropagators propagators;

  private ReplaySafeOpenTelemetry(Builder builder) {
    this.tracerProvider =
        new ReplaySafeTracerProvider(
            builder.tracerProviderBuilder.setIdGenerator(new ReplaySafeIdGenerator()).build());
    this.meterProvider = new ReplaySafeMeterProvider(builder.meterProviderBuilder.build());
    this.loggerProvider = new ReplaySafeLoggerProvider(builder.loggerProviderBuilder.build());
    this.propagators = builder.propagators;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  @Override
  public TracerProvider getTracerProvider() {
    return tracerProvider;
  }

  @Override
  public MeterProvider getMeterProvider() {
    return meterProvider;
  }

  @Override
  public LoggerProvider getLogsBridge() {
    return loggerProvider;
  }

  @Override
  public ContextPropagators getPropagators() {
    return propagators;
  }

  static boolean isRegisteredGlobally() {
    return GlobalOpenTelemetry.getTracerProvider() instanceof ReplaySafeTracerProvider;
  }

  /** Shuts down every provider. Call after the clients and workers using them have stopped. */
  @Override
  public void close() {
    tracerProvider.close();
    meterProvider.close();
    loggerProvider.close();
  }

  /** Every provider is optional; an unset one is built from the SDK's default builder. */
  public static final class Builder {
    private SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder();
    private SdkMeterProviderBuilder meterProviderBuilder = SdkMeterProvider.builder();
    private SdkLoggerProviderBuilder loggerProviderBuilder = SdkLoggerProvider.builder();
    private ContextPropagators propagators =
        ContextPropagators.create(
            TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()));

    private Builder() {}

    public Builder setTracerProviderBuilder(SdkTracerProviderBuilder tracerProviderBuilder) {
      this.tracerProviderBuilder = tracerProviderBuilder;
      return this;
    }

    public Builder setMeterProviderBuilder(SdkMeterProviderBuilder meterProviderBuilder) {
      this.meterProviderBuilder = meterProviderBuilder;
      return this;
    }

    public Builder setLoggerProviderBuilder(SdkLoggerProviderBuilder loggerProviderBuilder) {
      this.loggerProviderBuilder = loggerProviderBuilder;
      return this;
    }

    /**
     * The propagators returned by {@link ReplaySafeOpenTelemetry#getPropagators()}. Defaults to W3C
     * trace context plus baggage, which is what {@link OpenTelemetryPlugin} serializes into
     * Temporal headers when it is left to resolve its propagator from the global.
     */
    public Builder setPropagators(ContextPropagators propagators) {
      this.propagators = propagators;
      return this;
    }

    public ReplaySafeOpenTelemetry build() {
      return new ReplaySafeOpenTelemetry(this);
    }
  }

  private static final class ReplaySafeTracerProvider implements TracerProvider, Closeable {
    private final SdkTracerProvider delegate;

    private ReplaySafeTracerProvider(SdkTracerProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Tracer get(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeTracer(delegate.get(instrumentationScopeName), instrumentationScopeName);
    }

    @Override
    public Tracer get(
        @Nonnull String instrumentationScopeName, @Nonnull String instrumentationScopeVersion) {
      return new ReplaySafeTracer(
          delegate.get(instrumentationScopeName, instrumentationScopeVersion),
          instrumentationScopeName);
    }

    @Override
    public TracerBuilder tracerBuilder(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeTracerBuilder(
          delegate.tracerBuilder(instrumentationScopeName), instrumentationScopeName);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static final class ReplaySafeMeterProvider implements MeterProvider, Closeable {
    private final SdkMeterProvider delegate;

    private ReplaySafeMeterProvider(SdkMeterProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Meter get(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeMeter(delegate.get(instrumentationScopeName));
    }

    @Override
    public MeterBuilder meterBuilder(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeMeterBuilder(delegate.meterBuilder(instrumentationScopeName));
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static final class ReplaySafeMeterBuilder implements MeterBuilder {
    private final MeterBuilder delegate;

    private ReplaySafeMeterBuilder(MeterBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public MeterBuilder setSchemaUrl(@Nonnull String schemaUrl) {
      delegate.setSchemaUrl(schemaUrl);
      return this;
    }

    @Override
    public MeterBuilder setInstrumentationVersion(@Nonnull String instrumentationScopeVersion) {
      delegate.setInstrumentationVersion(instrumentationScopeVersion);
      return this;
    }

    @Override
    public Meter build() {
      return new ReplaySafeMeter(delegate.build());
    }
  }

  private static final class ReplaySafeLoggerProvider implements LoggerProvider, Closeable {
    private final SdkLoggerProvider delegate;

    private ReplaySafeLoggerProvider(SdkLoggerProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public Logger get(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeLogger(delegate.get(instrumentationScopeName));
    }

    @Override
    public LoggerBuilder loggerBuilder(@Nonnull String instrumentationScopeName) {
      return new ReplaySafeLoggerBuilder(delegate.loggerBuilder(instrumentationScopeName));
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static final class ReplaySafeLoggerBuilder implements LoggerBuilder {
    private final LoggerBuilder delegate;

    private ReplaySafeLoggerBuilder(LoggerBuilder delegate) {
      this.delegate = delegate;
    }

    @Override
    public LoggerBuilder setSchemaUrl(@Nonnull String schemaUrl) {
      delegate.setSchemaUrl(schemaUrl);
      return this;
    }

    @Override
    public LoggerBuilder setInstrumentationVersion(@Nonnull String instrumentationScopeVersion) {
      delegate.setInstrumentationVersion(instrumentationScopeVersion);
      return this;
    }

    @Override
    public Logger build() {
      return new ReplaySafeLogger(delegate.build());
    }
  }

  private static final class ReplaySafeTracerBuilder implements TracerBuilder {
    private final TracerBuilder delegate;
    private final String instrumentationScopeName;

    private ReplaySafeTracerBuilder(TracerBuilder delegate, String instrumentationScopeName) {
      this.delegate = delegate;
      this.instrumentationScopeName = instrumentationScopeName;
    }

    @Override
    public TracerBuilder setSchemaUrl(@Nonnull String schemaUrl) {
      delegate.setSchemaUrl(schemaUrl);
      return this;
    }

    @Override
    public TracerBuilder setInstrumentationVersion(@Nonnull String instrumentationScopeVersion) {
      delegate.setInstrumentationVersion(instrumentationScopeVersion);
      return this;
    }

    @Override
    public Tracer build() {
      return new ReplaySafeTracer(delegate.build(), instrumentationScopeName);
    }
  }
}
