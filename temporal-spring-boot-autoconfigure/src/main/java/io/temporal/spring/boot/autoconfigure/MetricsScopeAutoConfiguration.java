package io.temporal.spring.boot.autoconfigure;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter(
    name =
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnBean(MeterRegistry.class)
public class MetricsScopeAutoConfiguration {
  @Bean(name = "temporalMetricsScope", destroyMethod = "close")
  public Scope scope(
      // Spring Boot configures and exposes Micrometer MeterRegistry bean in the
      // spring-boot-starter-actuator dependency
      @Autowired(required = false) @Nullable MeterRegistry meterRegistry) {
    StatsReporter reporter = new MicrometerClientStatsReporter(meterRegistry);
    return new RootScopeBuilder()
        .reporter(reporter)
        .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
  }
}
