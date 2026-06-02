package io.temporal.aws.lambda;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** OpenTelemetry helper for {@link LambdaWorker}. */
public final class OtelLambdaWorker {
  public static final String OTEL_EXPORTER_OTLP_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
  public static final String OTEL_SERVICE_NAME = "OTEL_SERVICE_NAME";
  public static final String AWS_LAMBDA_FUNCTION_NAME = "AWS_LAMBDA_FUNCTION_NAME";
  public static final String DEFAULT_OTLP_ENDPOINT = "http://localhost:4317";
  public static final String DEFAULT_SERVICE_NAME = "temporal-lambda-worker";

  private static final AttributeKey<String> SERVICE_NAME_ATTRIBUTE =
      AttributeKey.stringKey("service.name");

  private OtelLambdaWorker() {}

  public static Builder newBuilder() {
    return new Builder(System.getenv());
  }

  static Builder newBuilder(Map<String, String> env) {
    return new Builder(env);
  }

  public static String getDefaultEndpoint() {
    return resolveEndpoint(System.getenv());
  }

  public static String getDefaultServiceName() {
    return resolveServiceName(System.getenv());
  }

  public static void configure(LambdaWorkerOptions options) {
    configure(options, builder -> {});
  }

  /**
   * Configures metrics, tracing interceptors, and per-invocation flushing for a Lambda worker.
   *
   * <p>By default this method creates an OpenTelemetry SDK with OTLP trace and metric exporters and
   * AWS X-Ray-compatible trace ID generation. If {@link Builder#setOpenTelemetry(OpenTelemetry)} is
   * used, the provided instance is used instead and exporters are not created.
   */
  public static void configure(LambdaWorkerOptions options, Consumer<Builder> configure) {
    Objects.requireNonNull(options, "options");
    Builder builder = newBuilder();
    Objects.requireNonNull(configure, "configure").accept(builder);
    builder.apply(options);
  }

  /**
   * Configures Temporal metrics with the default service name and reporting interval.
   *
   * <p>This helper only installs the metrics scope. It does not configure tracing interceptors or
   * register a flush hook.
   */
  public static void configureMetrics(LambdaWorkerOptions options, OpenTelemetry openTelemetry) {
    configureMetrics(options, openTelemetry, getDefaultServiceName(), Duration.ofSeconds(1));
  }

  /**
   * Configures Temporal metrics with an application-owned OpenTelemetry provider.
   *
   * <p>This helper only installs the metrics scope. It does not configure tracing interceptors or
   * register a flush hook.
   */
  public static void configureMetrics(
      LambdaWorkerOptions options,
      OpenTelemetry openTelemetry,
      String serviceName,
      Duration reportInterval) {
    Objects.requireNonNull(options, "options");
    OpenTelemetryStatsReporter reporter =
        new OpenTelemetryStatsReporter(
            Objects.requireNonNull(openTelemetry, "openTelemetry"),
            Objects.requireNonNull(serviceName, "serviceName"));
    Scope scope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(
                com.uber.m3.util.Duration.ofMillis(
                    requirePositive(reportInterval, "reportInterval").toMillis()));
    options.getWorkflowServiceStubsOptionsBuilder().setMetricsScope(scope);
  }

  /**
   * Configures Temporal tracing interceptors with an application-owned OpenTelemetry provider.
   *
   * <p>This helper only installs tracing interceptors. It does not configure metrics or register a
   * flush hook.
   */
  public static void configureTracing(LambdaWorkerOptions options, OpenTelemetry openTelemetry) {
    Objects.requireNonNull(options, "options");
    OpenTracingOptions tracingOptions =
        OpenTracingOptions.newBuilder()
            .setTracer(
                OpenTracingShim.createTracerShim(
                    Objects.requireNonNull(openTelemetry, "openTelemetry")))
            .build();
    appendClientInterceptor(options, new OpenTracingClientInterceptor(tracingOptions));
    appendWorkerInterceptor(options, new OpenTracingWorkerInterceptor(tracingOptions));
  }

  /**
   * Registers a per-invocation OpenTelemetry force-flush hook.
   *
   * <p>This helper only registers the flush hook. It does not configure metrics or tracing.
   */
  public static void configureFlushHook(
      LambdaWorkerOptions options, OpenTelemetry openTelemetry, Duration flushTimeout) {
    Objects.requireNonNull(options, "options");
    options.addShutdownHook(
        new OpenTelemetryFlushHook(
            Objects.requireNonNull(openTelemetry, "openTelemetry"),
            requireNonNegative(flushTimeout, "flushTimeout")));
  }

  static String resolveEndpoint(Map<String, String> env) {
    String endpoint = nonEmptyEnv(env, OTEL_EXPORTER_OTLP_ENDPOINT);
    return endpoint == null ? DEFAULT_OTLP_ENDPOINT : endpoint;
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
    private OpenTelemetry openTelemetry;
    private String endpoint;
    private String serviceName;
    private Duration metricsReportInterval = Duration.ofSeconds(1);
    private Duration flushTimeout = Duration.ofSeconds(10);
    private Runnable flushHook;
    private TelemetryFactory telemetryFactory = new DefaultTelemetryFactory();

    private Builder(Map<String, String> env) {
      this.env = Objects.requireNonNull(env, "env");
    }

    /**
     * Uses an application-owned OpenTelemetry instance instead of creating an SDK and exporters.
     */
    public Builder setOpenTelemetry(OpenTelemetry openTelemetry) {
      this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
      return this;
    }

    /** Sets the OTLP metric and trace exporter endpoint used by the default SDK setup. */
    public Builder setEndpoint(String endpoint) {
      this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
      return this;
    }

    /** Sets the service name used by the default SDK resource and Temporal metrics reporter. */
    public Builder setServiceName(String serviceName) {
      this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
      return this;
    }

    /** Sets the interval used by the Tally metrics scope and periodic metric reader. */
    public Builder setMetricsReportInterval(Duration metricsReportInterval) {
      this.metricsReportInterval = requirePositive(metricsReportInterval, "metricsReportInterval");
      return this;
    }

    /** Sets how long the per-invocation OpenTelemetry flush hook waits for provider flushing. */
    public Builder setFlushTimeout(Duration flushTimeout) {
      this.flushTimeout = requireNonNegative(flushTimeout, "flushTimeout");
      return this;
    }

    /** Overrides the per-invocation flush hook. */
    public Builder setFlushHook(Runnable flushHook) {
      this.flushHook = Objects.requireNonNull(flushHook, "flushHook");
      return this;
    }

    public String getEndpoint() {
      return endpoint == null ? resolveEndpoint(env) : endpoint;
    }

    public String getServiceName() {
      return serviceName == null ? resolveServiceName(env) : serviceName;
    }

    Builder setTelemetryFactory(TelemetryFactory telemetryFactory) {
      this.telemetryFactory = Objects.requireNonNull(telemetryFactory, "telemetryFactory");
      return this;
    }

    OpenTelemetry createOpenTelemetry() {
      return telemetryFactory.create(
          getEndpoint(), getServiceName(), metricsReportInterval, flushTimeout);
    }

    void apply(LambdaWorkerOptions options) {
      OpenTelemetry resolvedOpenTelemetry =
          openTelemetry == null ? createOpenTelemetry() : openTelemetry;
      configureMetrics(options, resolvedOpenTelemetry, getServiceName(), metricsReportInterval);
      configureTracing(options, resolvedOpenTelemetry);
      if (flushHook == null) {
        configureFlushHook(options, resolvedOpenTelemetry, flushTimeout);
      } else {
        options.addShutdownHook(flushHook);
      }
    }
  }

  interface TelemetryFactory {
    OpenTelemetry create(
        String endpoint, String serviceName, Duration metricsReportInterval, Duration flushTimeout);
  }

  private static final class DefaultTelemetryFactory implements TelemetryFactory {
    @Override
    public OpenTelemetry create(
        String endpoint,
        String serviceName,
        Duration metricsReportInterval,
        Duration flushTimeout) {
      Resource resource =
          Resource.getDefault()
              .merge(Resource.create(Attributes.of(SERVICE_NAME_ATTRIBUTE, serviceName)));
      MetricExporter metricExporter =
          OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build();
      SpanExporter spanExporter = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build();

      SdkMeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(resource)
              .registerMetricReader(
                  PeriodicMetricReader.builder(metricExporter)
                      .setInterval(metricsReportInterval)
                      .build())
              .build();
      SdkTracerProvider tracerProvider =
          SdkTracerProvider.builder()
              .setResource(resource)
              .setIdGenerator(AwsXrayIdGenerator.getInstance())
              .addSpanProcessor(
                  BatchSpanProcessor.builder(spanExporter).setExporterTimeout(flushTimeout).build())
              .build();

      return OpenTelemetrySdk.builder()
          .setMeterProvider(meterProvider)
          .setTracerProvider(tracerProvider)
          .build();
    }
  }

  private static String nonEmptyEnv(Map<String, String> env, String name) {
    if (env == null) {
      return null;
    }
    String value = env.get(name);
    return value == null || value.trim().isEmpty() ? null : value;
  }

  private static void appendClientInterceptor(
      LambdaWorkerOptions options, WorkflowClientInterceptor interceptor) {
    WorkflowClientOptions raw = options.getWorkflowClientOptionsBuilder().build();
    WorkflowClientInterceptor[] existing = raw.getInterceptors();
    int existingLength = existing == null ? 0 : existing.length;
    WorkflowClientInterceptor[] interceptors =
        existingLength == 0
            ? new WorkflowClientInterceptor[1]
            : Arrays.copyOf(existing, existingLength + 1);
    interceptors[existingLength] = interceptor;
    options.getWorkflowClientOptionsBuilder().setInterceptors(interceptors);
  }

  private static void appendWorkerInterceptor(
      LambdaWorkerOptions options, WorkerInterceptor interceptor) {
    WorkerFactoryOptions raw = options.getWorkerFactoryOptionsBuilder().build();
    WorkerInterceptor[] existing = raw.getWorkerInterceptors();
    int existingLength = existing == null ? 0 : existing.length;
    WorkerInterceptor[] interceptors =
        existingLength == 0
            ? new WorkerInterceptor[1]
            : Arrays.copyOf(existing, existingLength + 1);
    interceptors[existingLength] = interceptor;
    options.getWorkerFactoryOptionsBuilder().setWorkerInterceptors(interceptors);
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static Duration requireNonNegative(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
