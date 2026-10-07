package io.temporal.opentelemetry.v2;

import io.opentelemetry.context.ContextStorage;
import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.opentelemetry.v2.internal.InterceptorTracer;
import io.temporal.opentelemetry.v2.internal.OpenTelemetryContextPropagator;
import io.temporal.opentelemetry.v2.internal.TemporalContextStorage;

/**
 * OpenTelemetry v2 plugin for Temporal clients and workers.
 *
 * <p>Set it on {@code WorkflowServiceStubsOptions} or {@code WorkflowClientOptions}. The SDK
 * propagates plugins down that chain.
 *
 * <p>Register a {@link ReplaySafeOpenTelemetry} with {@code GlobalOpenTelemetry.set} before calling
 * {@link Builder#build()}.
 */
@Experimental
public final class OpenTelemetryPlugin extends SimplePlugin {
  public static final String NAME = "io.temporal.opentelemetry.v2";

  private OpenTelemetryPlugin(InterceptorTracer tracer, String headerKey) {
    super(
        SimplePlugin.newBuilder(NAME)
            .addClientInterceptors(new OpenTelemetryClientInterceptor(tracer))
            .addScheduleClientInterceptors(new OpenTelemetryScheduleClientInterceptor(tracer))
            .addActivityClientInterceptors(new OpenTelemetryActivityClientInterceptor(tracer))
            .addNexusClientInterceptors(new OpenTelemetryNexusClientInterceptor(tracer))
            .addWorkerInterceptors(new OpenTelemetryWorkerInterceptor(tracer))
            .addContextPropagators(new OpenTelemetryContextPropagator(headerKey)));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Every option is optional; an unset one keeps its default. */
  public static final class Builder {
    private String headerKey = "_tracer-data";
    private boolean addTemporalSpans;

    private Builder() {}

    /**
     * The Temporal header key to serialize the span to. Defaults to {@code _tracer-data}, which
     * Temporal uses; overriding it breaks trace continuity with workers using the standard key.
     */
    public Builder setHeaderKey(String headerKey) {
      this.headerKey = headerKey;
      return this;
    }

    /**
     * Whether to create spans for Temporal operations such as StartWorkflow, RunWorkflow, and
     * RunActivity. Defaults to false: trace context still propagates through Temporal headers, so
     * spans created by application code remain connected.
     */
    public Builder setAddTemporalSpans(boolean addTemporalSpans) {
      this.addTemporalSpans = addTemporalSpans;
      return this;
    }

    public OpenTelemetryPlugin build() {
      if (!ReplaySafeOpenTelemetry.isRegisteredGlobally()) {
        throw new IllegalStateException(
            "the global OpenTelemetry must be a ReplaySafeOpenTelemetry; build one with "
                + "ReplaySafeOpenTelemetry.newBuilder() and register it with "
                + "GlobalOpenTelemetry.set before building this plugin");
      }
      if (!(ContextStorage.get() instanceof TemporalContextStorage)) {
        throw new IllegalStateException(
            "the OpenTelemetry ContextStorage initialized before ReplaySafeOpenTelemetry could "
                + "wrap it; build ReplaySafeOpenTelemetry before anything uses the OpenTelemetry "
                + "Context");
      }
      return new OpenTelemetryPlugin(new InterceptorTracer(headerKey, addTemporalSpans), headerKey);
    }
  }
}
