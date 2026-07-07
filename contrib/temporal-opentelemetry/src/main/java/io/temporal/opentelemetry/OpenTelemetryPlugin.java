package io.temporal.opentelemetry;

import com.uber.m3.tally.Scope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * OpenTelemetry plugin for Temporal workers.
 *
 * <p>The plugin installs an OpenTelemetry-backed Tally metrics scope on service stubs and
 * OpenTelemetry-backed tracing interceptors on workflow clients and worker factories.
 */
@Experimental
public final class OpenTelemetryPlugin extends SimplePlugin {
  public static final String NAME = "io.temporal.opentelemetry";

  private final Map<String, String> env;
  private final OpenTelemetryWorker.TelemetryFactory telemetryFactory;
  private final String endpoint;
  private final String serviceName;
  private final Duration metricsReportInterval;
  private final Duration flushTimeout;
  private final Runnable flushHook;
  private final IdGenerator idGenerator;
  private final boolean flushOnWorkerFactoryShutdown;
  private final List<Scope> metricsScopes = new ArrayList<>();

  private OpenTelemetry openTelemetry;

  private OpenTelemetryPlugin(Builder builder) {
    super(NAME);
    this.env = builder.env;
    this.telemetryFactory = builder.telemetryFactory;
    this.openTelemetry = builder.openTelemetry;
    this.endpoint = builder.endpoint;
    this.serviceName = builder.serviceName;
    this.metricsReportInterval = builder.metricsReportInterval;
    this.flushTimeout = builder.flushTimeout;
    this.flushHook = builder.flushHook;
    this.idGenerator = builder.idGenerator;
    this.flushOnWorkerFactoryShutdown = builder.flushOnWorkerFactoryShutdown;
  }

  public static Builder newBuilder() {
    return new Builder(System.getenv());
  }

  public static Builder newBuilder(@Nonnull Map<String, String> env) {
    return new Builder(env);
  }

  public String getEndpoint() {
    return endpoint == null ? OpenTelemetryWorker.resolveEndpoint(env) : endpoint;
  }

  public String getServiceName() {
    return serviceName == null ? OpenTelemetryWorker.resolveServiceName(env) : serviceName;
  }

  public synchronized OpenTelemetry getOpenTelemetry() {
    if (openTelemetry == null) {
      openTelemetry =
          telemetryFactory.create(
              getEndpoint(), getServiceName(), metricsReportInterval, flushTimeout, idGenerator);
    }
    return openTelemetry;
  }

  @Override
  public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
    Scope scope =
        OpenTelemetryWorker.createMetricsScope(
            getOpenTelemetry(), getServiceName(), metricsReportInterval);
    synchronized (metricsScopes) {
      metricsScopes.add(scope);
    }
    builder.setMetricsScope(scope);
  }

  @Override
  public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
    OpenTracingOptions tracingOptions =
        OpenTelemetryWorker.createOpenTracingOptions(getOpenTelemetry());
    OpenTelemetryWorker.appendClientInterceptor(
        builder, new io.temporal.opentracing.OpenTracingClientInterceptor(tracingOptions));
  }

  @Override
  public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
    OpenTracingOptions tracingOptions =
        OpenTelemetryWorker.createOpenTracingOptions(getOpenTelemetry());
    OpenTelemetryWorker.appendWorkerInterceptor(
        builder, new io.temporal.opentracing.OpenTracingWorkerInterceptor(tracingOptions));
  }

  @Override
  public void shutdownWorkerFactory(
      @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
    next.accept(factory);
    if (flushOnWorkerFactoryShutdown) {
      newFlushHook().run();
    }
  }

  /**
   * Creates a flush hook that reports buffered Tally metrics before force-flushing OpenTelemetry
   * providers.
   */
  public TimedShutdownHook newFlushHook() {
    return new TimedShutdownHook() {
      @Override
      public void run() {
        flush(flushTimeout);
      }

      @Override
      public void run(@Nonnull Duration timeout) {
        flush(timeout);
      }
    };
  }

  private void flush(Duration timeout) {
    for (Scope scope : drainMetricsScopes()) {
      new TallyScopeFlushHook(scope).run();
    }
    if (flushHook == null) {
      new OpenTelemetryFlushHook(getOpenTelemetry(), flushTimeout).run(timeout);
    } else {
      flushHook.run();
    }
  }

  private List<Scope> drainMetricsScopes() {
    synchronized (metricsScopes) {
      List<Scope> scopes = new ArrayList<>(metricsScopes);
      metricsScopes.clear();
      return scopes;
    }
  }

  /** Builder for {@link OpenTelemetryPlugin}. */
  public static final class Builder {
    private final Map<String, String> env;
    private OpenTelemetry openTelemetry;
    private String endpoint;
    private String serviceName;
    private Duration metricsReportInterval = OpenTelemetryWorker.DEFAULT_METRICS_REPORT_INTERVAL;
    private Duration flushTimeout = Duration.ofSeconds(10);
    private Runnable flushHook;
    private IdGenerator idGenerator;
    private OpenTelemetryWorker.TelemetryFactory telemetryFactory =
        new OpenTelemetryWorker.DefaultTelemetryFactory();
    private boolean flushOnWorkerFactoryShutdown = true;

    private Builder(Map<String, String> env) {
      this.env = Objects.requireNonNull(env, "env");
    }

    /**
     * Uses an application-owned OpenTelemetry instance instead of creating an SDK and exporters.
     */
    public Builder setOpenTelemetry(@Nonnull OpenTelemetry openTelemetry) {
      this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
      return this;
    }

    /** Sets the OTLP metric and trace exporter endpoint used by the default SDK setup. */
    public Builder setEndpoint(@Nonnull String endpoint) {
      this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
      return this;
    }

    /** Sets the service name used by the default SDK resource and Temporal metrics reporter. */
    public Builder setServiceName(@Nonnull String serviceName) {
      this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
      return this;
    }

    /** Sets the interval used by the Tally metrics scope and periodic metric reader. */
    public Builder setMetricsReportInterval(@Nonnull Duration metricsReportInterval) {
      this.metricsReportInterval =
          OpenTelemetryWorker.requirePositive(metricsReportInterval, "metricsReportInterval");
      return this;
    }

    /** Sets how long the OpenTelemetry flush hook waits for provider flushing. */
    public Builder setFlushTimeout(@Nonnull Duration flushTimeout) {
      this.flushTimeout = OpenTelemetryWorker.requireNonNegative(flushTimeout, "flushTimeout");
      return this;
    }

    /** Overrides the OpenTelemetry provider flush hook. */
    public Builder setFlushHook(@Nonnull Runnable flushHook) {
      this.flushHook = Objects.requireNonNull(flushHook, "flushHook");
      return this;
    }

    /**
     * Controls whether the plugin flushes when a {@link WorkerFactory} is shut down.
     *
     * <p>Serverless integrations should disable this and use {@link
     * OpenTelemetryPlugin#newFlushHook()} from their per-invocation cleanup path.
     */
    public Builder setFlushOnWorkerFactoryShutdown(boolean flushOnWorkerFactoryShutdown) {
      this.flushOnWorkerFactoryShutdown = flushOnWorkerFactoryShutdown;
      return this;
    }

    /** Sets the trace ID generator used by the default SDK setup. */
    public Builder setIdGenerator(@Nonnull IdGenerator idGenerator) {
      this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
      return this;
    }

    public String getEndpoint() {
      return endpoint == null ? OpenTelemetryWorker.resolveEndpoint(env) : endpoint;
    }

    public String getServiceName() {
      return serviceName == null ? OpenTelemetryWorker.resolveServiceName(env) : serviceName;
    }

    Builder setTelemetryFactory(OpenTelemetryWorker.TelemetryFactory telemetryFactory) {
      this.telemetryFactory = Objects.requireNonNull(telemetryFactory, "telemetryFactory");
      return this;
    }

    public OpenTelemetry createOpenTelemetry() {
      return telemetryFactory.create(
          getEndpoint(), getServiceName(), metricsReportInterval, flushTimeout, idGenerator);
    }

    public OpenTelemetryPlugin build() {
      return new OpenTelemetryPlugin(this);
    }
  }
}
