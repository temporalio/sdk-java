package io.temporal.spring.boot.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.opentracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    afterName = {
      "org.springframework.cloud.sleuth.autoconfig.otel.OtelAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
      "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"
    })
@ConditionalOnClass(io.opentelemetry.api.OpenTelemetry.class)
@ConditionalOnBean(io.opentelemetry.api.OpenTelemetry.class)
public class OpenTracingAutoConfiguration {
  @ConditionalOnMissingBean(Tracer.class)
  @Bean(name = "temporalOtTracer")
  public Tracer openTracingTracer(@Autowired OpenTelemetry otel) {
    return OpenTracingShim.createTracerShim(otel);
  }
}
