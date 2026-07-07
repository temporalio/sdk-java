package io.temporal.aws.lambda;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.temporal.opentelemetry.OpenTelemetryWorker;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** OpenTelemetry configuration helper for {@link LambdaWorker}. */
public final class OtelLambdaWorkerConfigurationHelper {
  public static final String OTEL_EXPORTER_OTLP_ENDPOINT =
      OpenTelemetryWorker.OTEL_EXPORTER_OTLP_ENDPOINT;
  public static final String OTEL_SERVICE_NAME = OpenTelemetryWorker.OTEL_SERVICE_NAME;
  public static final String AWS_LAMBDA_FUNCTION_NAME = "AWS_LAMBDA_FUNCTION_NAME";
  public static final String DEFAULT_OTLP_ENDPOINT = OpenTelemetryWorker.DEFAULT_OTLP_ENDPOINT;
  public static final String DEFAULT_SERVICE_NAME = "temporal-lambda-worker";

  private OtelLambdaWorkerConfigurationHelper() {}

  public static Builder newBuilder() {
    return new Builder(System.getenv());
  }

  static Builder newBuilder(Map<String, String> env) {
    return new Builder(env);
  }

  public static String getDefaultEndpoint() {
    return OpenTelemetryWorker.getDefaultEndpoint();
  }

  public static String getDefaultServiceName() {
    return resolveServiceName(System.getenv());
  }

  public static void configure(@Nonnull LambdaWorkerOptions.Builder options) {
    configure(options, builder -> {});
  }

  /**
   * Configures metrics, tracing interceptors, and per-invocation flushing for a Lambda worker.
   *
   * <p>By default this method creates an OpenTelemetry SDK with OTLP trace and metric exporters and
   * AWS X-Ray-compatible trace ID generation. If {@link Builder#setOpenTelemetry(OpenTelemetry)} is
   * used, the provided instance is used instead and exporters are not created.
   */
  public static void configure(
      @Nonnull LambdaWorkerOptions.Builder options, @Nonnull Consumer<Builder> configure) {
    Objects.requireNonNull(options, "options");
    Builder builder = newBuilder();
    Objects.requireNonNull(configure, "configure").accept(builder);
    builder.apply(options);
  }

  /**
   * Configures Temporal metrics with the default service name and reporting interval.
   *
   * <p>This helper installs the metrics scope and registers a hook that reports buffered Tally
   * metrics before provider flushing. It does not configure tracing interceptors or an
   * OpenTelemetry provider flush hook.
   */
  public static void configureMetrics(
      @Nonnull LambdaWorkerOptions.Builder options, @Nonnull OpenTelemetry openTelemetry) {
    configureMetrics(options, openTelemetry, getDefaultServiceName(), Duration.ofSeconds(1));
  }

  /**
   * Configures Temporal metrics with an application-owned OpenTelemetry provider.
   *
   * <p>This helper installs the metrics scope and registers a hook that reports buffered Tally
   * metrics before provider flushing. It does not configure tracing interceptors or an
   * OpenTelemetry provider flush hook.
   */
  public static void configureMetrics(
      @Nonnull LambdaWorkerOptions.Builder options,
      @Nonnull OpenTelemetry openTelemetry,
      @Nonnull String serviceName,
      @Nonnull Duration reportInterval) {
    Objects.requireNonNull(options, "options");
    OpenTelemetryWorker.configureMetrics(
        options.getWorkflowServiceStubsOptionsBuilder(),
        options::addShutdownHook,
        openTelemetry,
        serviceName,
        reportInterval);
  }

  /**
   * Configures Temporal tracing interceptors with an application-owned OpenTelemetry provider.
   *
   * <p>This helper only installs tracing interceptors. It does not configure metrics or register a
   * flush hook.
   */
  public static void configureTracing(
      @Nonnull LambdaWorkerOptions.Builder options, @Nonnull OpenTelemetry openTelemetry) {
    Objects.requireNonNull(options, "options");
    OpenTelemetryWorker.configureTracing(
        options.getWorkflowClientOptionsBuilder(),
        options.getWorkerFactoryOptionsBuilder(),
        openTelemetry);
  }

  /**
   * Registers a per-invocation OpenTelemetry force-flush hook.
   *
   * <p>This helper only registers the flush hook. It does not configure metrics or tracing.
   */
  public static void configureFlushHook(
      @Nonnull LambdaWorkerOptions.Builder options,
      @Nonnull OpenTelemetry openTelemetry,
      @Nonnull Duration flushTimeout) {
    Objects.requireNonNull(options, "options");
    OpenTelemetryWorker.configureFlushHook(options::addShutdownHook, openTelemetry, flushTimeout);
  }

  static String resolveServiceName(Map<String, String> env) {
    String serviceName = nonEmptyEnv(env, OTEL_SERVICE_NAME);
    if (serviceName != null) {
      return serviceName;
    }
    serviceName = nonEmptyEnv(env, AWS_LAMBDA_FUNCTION_NAME);
    return serviceName == null ? DEFAULT_SERVICE_NAME : serviceName;
  }

  public static final class Builder {
    private final Map<String, String> env;
    private final OpenTelemetryWorker.Builder delegate;
    private OpenTelemetry openTelemetry;
    private String serviceName;
    private Duration metricsReportInterval = Duration.ofSeconds(1);
    private Duration flushTimeout = Duration.ofSeconds(10);
    private TelemetryFactory telemetryFactory;

    private Builder(Map<String, String> env) {
      this.env = Objects.requireNonNull(env, "env");
      this.delegate =
          OpenTelemetryWorker.newBuilder(env).setIdGenerator(AwsXrayIdGenerator.getInstance());
    }

    /**
     * Uses an application-owned OpenTelemetry instance instead of creating an SDK and exporters.
     */
    public Builder setOpenTelemetry(@Nonnull OpenTelemetry openTelemetry) {
      this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
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
      delegate.setServiceName(serviceName);
      return this;
    }

    /** Sets the interval used by the Tally metrics scope and periodic metric reader. */
    public Builder setMetricsReportInterval(@Nonnull Duration metricsReportInterval) {
      delegate.setMetricsReportInterval(metricsReportInterval);
      this.metricsReportInterval = metricsReportInterval;
      return this;
    }

    /** Sets how long the per-invocation OpenTelemetry flush hook waits for provider flushing. */
    public Builder setFlushTimeout(@Nonnull Duration flushTimeout) {
      delegate.setFlushTimeout(flushTimeout);
      this.flushTimeout = flushTimeout;
      return this;
    }

    /** Overrides the per-invocation flush hook. */
    public Builder setFlushHook(@Nonnull Runnable flushHook) {
      delegate.setFlushHook(flushHook);
      return this;
    }

    public String getEndpoint() {
      return delegate.getEndpoint();
    }

    public String getServiceName() {
      return serviceName == null ? resolveServiceName(env) : serviceName;
    }

    Builder setTelemetryFactory(TelemetryFactory telemetryFactory) {
      this.telemetryFactory = Objects.requireNonNull(telemetryFactory, "telemetryFactory");
      return this;
    }

    OpenTelemetry createOpenTelemetry() {
      applyLambdaServiceNameDefault();
      if (telemetryFactory != null) {
        return openTelemetry == null ? getOrCreateTestOpenTelemetry() : openTelemetry;
      }
      return delegate.createOpenTelemetry();
    }

    void apply(LambdaWorkerOptions.Builder options) {
      applyLambdaDefaults();
      delegate.apply(
          options.getWorkflowServiceStubsOptionsBuilder(),
          options.getWorkflowClientOptionsBuilder(),
          options.getWorkerFactoryOptionsBuilder(),
          options::addShutdownHook);
    }

    private void applyLambdaDefaults() {
      applyLambdaServiceNameDefault();
      if (telemetryFactory != null && openTelemetry == null) {
        delegate.setOpenTelemetry(getOrCreateTestOpenTelemetry());
      }
    }

    private void applyLambdaServiceNameDefault() {
      delegate.setServiceName(getServiceName());
    }

    private OpenTelemetry getOrCreateTestOpenTelemetry() {
      if (openTelemetry == null) {
        openTelemetry = createTestOpenTelemetry();
      }
      return openTelemetry;
    }

    private OpenTelemetry createTestOpenTelemetry() {
      return telemetryFactory.create(
          getEndpoint(),
          getServiceName(),
          metricsReportInterval,
          flushTimeout,
          AwsXrayIdGenerator.getInstance());
    }
  }

  interface TelemetryFactory {
    OpenTelemetry create(
        String endpoint,
        String serviceName,
        Duration metricsReportInterval,
        Duration flushTimeout,
        IdGenerator idGenerator);
  }

  private static String nonEmptyEnv(Map<String, String> env, String name) {
    if (env == null) {
      return null;
    }
    String value = env.get(name);
    return value == null || value.trim().isEmpty() ? null : value;
  }
}
