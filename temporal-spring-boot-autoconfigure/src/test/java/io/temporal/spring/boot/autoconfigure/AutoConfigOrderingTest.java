package io.temporal.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests that {@link MetricsScopeAutoConfiguration} and {@link OpenTracingAutoConfiguration}
 * correctly order themselves after their dependency auto-configurations via
 * {@code @AutoConfiguration(afterName=...)}.
 *
 * <p>Without correct ordering, {@code @ConditionalOnBean} evaluates before the dependency beans
 * exist, silently skipping our entire auto-configuration. This happens because our FQCNs ({@code
 * io.temporal...}) sort alphabetically before Spring's ({@code org.springframework...}), which is
 * the default processing order when no ordering constraints are specified.
 */
class AutoConfigOrderingTest {

  /**
   * Try to load a class by name, returning the first match found. Returns null if none of the class
   * names exist on the classpath.
   */
  private static Class<?> findClass(String... classNames) {
    for (String name : classNames) {
      try {
        return Class.forName(name);
      } catch (ClassNotFoundException ignored) {
      }
    }
    return null;
  }

  /**
   * Verifies that {@link MetricsScopeAutoConfiguration} runs after {@code
   * CompositeMeterRegistryAutoConfiguration} and finds the {@code MeterRegistry} bean it creates.
   * The {@code CompositeMeterRegistryAutoConfiguration} class moved packages in Spring Boot 4 —
   * both old and new names must be in {@code @AutoConfiguration(afterName=...)}.
   */
  @Test
  void metricsScopeCreatedWithAutoConfigOrdering() {
    Class<?> compositeClass =
        findClass(
            // SB 2.7 / 3.x
            "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
            // SB 4.0
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration");

    assumeTrue(compositeClass != null, "CompositeMeterRegistryAutoConfiguration not on classpath");

    // MetricsAutoConfiguration provides the Clock bean that CompositeMeterRegistryAutoConfiguration
    // inner configs need to activate.
    List<Class<?>> autoConfigs = new ArrayList<>();
    autoConfigs.add(MetricsScopeAutoConfiguration.class);
    autoConfigs.add(compositeClass);
    Class<?> metricsAutoConfig =
        findClass(
            // SB 2.7 / 3.x
            "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
            // SB 4.0
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration");
    if (metricsAutoConfig != null) {
      autoConfigs.add(metricsAutoConfig);
    }

    // A SimpleMeterRegistry gives the CompositeMeterRegistry a concrete child
    // to delegate to; without it, increments are silently dropped.
    SimpleMeterRegistry testRegistry = new SimpleMeterRegistry();

    new ApplicationContextRunner()
        .withBean(MeterRegistry.class, () -> testRegistry)
        .withConfiguration(AutoConfigurations.of(autoConfigs.toArray(new Class<?>[0])))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("temporalMetricsScope");

              // Verify metrics flow end-to-end: tally Scope → MicrometerClientStatsReporter
              // → MeterRegistry. Record a counter, flush via close(), and check Micrometer.
              Scope scope = context.getBean("temporalMetricsScope", Scope.class);
              scope.counter("test_counter").inc(1);
              scope.close();

              assertThat(testRegistry.find("test_counter").counter())
                  .as("Counter recorded via tally Scope should appear in MeterRegistry")
                  .isNotNull();
              assertThat(testRegistry.find("test_counter").counter().count()).isEqualTo(1.0);
            });
  }

  /**
   * Verifies that {@link OpenTracingAutoConfiguration} runs after the OpenTelemetry
   * auto-configuration and finds the {@code OpenTelemetry} bean it creates. The producing class
   * moved across Spring Boot versions:
   *
   * <ul>
   *   <li>SB 3.2+: {@code actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration}
   *   <li>SB 4.0: {@code opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration}
   * </ul>
   *
   * <p>On SB 2.7 (no native OTel auto-config) this test is skipped.
   */
  @Test
  void openTracingTracerCreatedWithAutoConfigOrdering() {
    Class<?> otelAutoConfig =
        findClass(
            // SB 3.2+ (generic OTel auto-config, uses ObjectProvider for optional deps)
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            // SB 4.0 (relocated to separate module)
            "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration");

    assumeTrue(otelAutoConfig != null, "OpenTelemetry auto-configuration not on classpath");

    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(OpenTracingAutoConfiguration.class, otelAutoConfig))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("temporalOtTracer");

              // Verify the Tracer is backed by OpenTelemetry (not a noop):
              // build a span and confirm it produces a valid trace ID.
              Tracer tracer = context.getBean("temporalOtTracer", Tracer.class);
              Span span = tracer.buildSpan("test-operation").start();
              try {
                assertThat(span.context().toTraceId())
                    .as("Tracer should produce valid trace IDs (backed by OpenTelemetry)")
                    .isNotBlank()
                    .isNotEqualTo("00000000000000000000000000000000");
              } finally {
                span.finish();
              }
            });
  }
}
