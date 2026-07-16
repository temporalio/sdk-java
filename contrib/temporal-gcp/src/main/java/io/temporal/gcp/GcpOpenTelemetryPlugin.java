package io.temporal.gcp;

import io.opentelemetry.api.OpenTelemetry;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.opentelemetry.OpenTelemetryPlugin;
import io.temporal.opentelemetry.OpenTelemetryWorker;
import io.temporal.opentelemetry.TimedShutdownHook;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * OpenTelemetry plugin with defaults for Temporal workers running on Google Cloud Run, primarily in
 * worker pools.
 */
@Experimental
public final class GcpOpenTelemetryPlugin extends SimplePlugin {
  public static final String NAME = OpenTelemetryPlugin.NAME;
  public static final String OTEL_EXPORTER_OTLP_ENDPOINT =
      OpenTelemetryWorker.OTEL_EXPORTER_OTLP_ENDPOINT;
  public static final String OTEL_SERVICE_NAME = OpenTelemetryWorker.OTEL_SERVICE_NAME;
  public static final String CLOUD_RUN_WORKER_POOL = "CLOUD_RUN_WORKER_POOL";
  public static final String K_SERVICE = "K_SERVICE";
  public static final String DEFAULT_OTLP_ENDPOINT = OpenTelemetryWorker.DEFAULT_OTLP_ENDPOINT;
  public static final String DEFAULT_SERVICE_NAME = OpenTelemetryWorker.DEFAULT_SERVICE_NAME;

  private final OpenTelemetryPlugin delegate;

  private GcpOpenTelemetryPlugin(Builder builder) {
    super(NAME);
    this.delegate = builder.buildDelegate();
  }

  public static Builder newBuilder() {
    return new Builder(System.getenv());
  }

  public static Builder newBuilder(@Nonnull Map<String, String> env) {
    return new Builder(env);
  }

  public String getEndpoint() {
    return delegate.getEndpoint();
  }

  public String getServiceName() {
    return delegate.getServiceName();
  }

  public OpenTelemetry getOpenTelemetry() {
    return delegate.getOpenTelemetry();
  }

  /**
   * Creates a flush hook that reports buffered Temporal metrics before force-flushing OpenTelemetry
   * providers.
   */
  public TimedShutdownHook newFlushHook() {
    return delegate.newFlushHook();
  }

  @Override
  public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
    delegate.configureServiceStubs(builder);
  }

  @Override
  public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
    delegate.configureWorkflowClient(builder);
  }

  @Override
  public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
    delegate.configureWorkerFactory(builder);
  }

  @Override
  public void shutdownWorkerFactory(
      @Nonnull WorkerFactory factory, @Nonnull Consumer<WorkerFactory> next) {
    delegate.shutdownWorkerFactory(factory, next);
  }

  /** Builder for {@link GcpOpenTelemetryPlugin}. */
  public static final class Builder {
    private final Map<String, String> env;
    private final OpenTelemetryPlugin.Builder delegate;
    private String serviceName;

    private Builder(Map<String, String> env) {
      this.env = Objects.requireNonNull(env, "env");
      this.delegate = OpenTelemetryPlugin.newBuilder(env).setFlushOnWorkerFactoryShutdown(false);
    }

    /**
     * Uses an application-owned OpenTelemetry instance instead of creating an SDK and exporters.
     */
    public Builder setOpenTelemetry(@Nonnull OpenTelemetry openTelemetry) {
      delegate.setOpenTelemetry(openTelemetry);
      return this;
    }

    /** Sets the OTLP metric and trace exporter endpoint used by the default SDK setup. */
    public Builder setEndpoint(@Nonnull String endpoint) {
      delegate.setEndpoint(endpoint);
      return this;
    }

    /** Sets the service name used by the default SDK resource and Temporal metrics reporter. */
    public Builder setServiceName(@Nonnull String serviceName) {
      this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
      return this;
    }

    /** Sets the interval used by the Temporal metrics scope and periodic metric reader. */
    public Builder setMetricsReportInterval(@Nonnull Duration metricsReportInterval) {
      delegate.setMetricsReportInterval(metricsReportInterval);
      return this;
    }

    /** Sets how long the OpenTelemetry flush hook waits for provider flushing. */
    public Builder setFlushTimeout(@Nonnull Duration flushTimeout) {
      delegate.setFlushTimeout(flushTimeout);
      return this;
    }

    /** Overrides the OpenTelemetry provider flush hook. */
    public Builder setFlushHook(@Nonnull Runnable flushHook) {
      delegate.setFlushHook(flushHook);
      return this;
    }

    /**
     * Controls whether the plugin flushes immediately after {@link WorkerFactory#shutdown()} or
     * {@link WorkerFactory#shutdownNow()} initiates shutdown.
     *
     * <p>This is disabled by default because worker-factory shutdown is asynchronous. Cloud Run
     * applications should wait for worker termination and then run {@link
     * GcpOpenTelemetryPlugin#newFlushHook()} with the remaining shutdown time.
     */
    public Builder setFlushOnWorkerFactoryShutdown(boolean flushOnWorkerFactoryShutdown) {
      delegate.setFlushOnWorkerFactoryShutdown(flushOnWorkerFactoryShutdown);
      return this;
    }

    public String getEndpoint() {
      return delegate.getEndpoint();
    }

    public String getServiceName() {
      return serviceName == null ? resolveServiceName(env) : serviceName;
    }

    public OpenTelemetry createOpenTelemetry() {
      applyServiceNameDefault();
      return delegate.createOpenTelemetry();
    }

    public GcpOpenTelemetryPlugin build() {
      return new GcpOpenTelemetryPlugin(this);
    }

    private OpenTelemetryPlugin buildDelegate() {
      applyServiceNameDefault();
      return delegate.build();
    }

    private void applyServiceNameDefault() {
      delegate.setServiceName(getServiceName());
    }
  }

  static String resolveServiceName(Map<String, String> env) {
    String value = nonEmptyEnv(env, OTEL_SERVICE_NAME);
    if (value != null) {
      return value;
    }
    value = nonEmptyEnv(env, CLOUD_RUN_WORKER_POOL);
    if (value != null) {
      return value;
    }
    value = nonEmptyEnv(env, K_SERVICE);
    return value == null ? DEFAULT_SERVICE_NAME : value;
  }

  private static String nonEmptyEnv(Map<String, String> env, String name) {
    String value = env.get(name);
    return value == null || value.trim().isEmpty() ? null : value;
  }
}
