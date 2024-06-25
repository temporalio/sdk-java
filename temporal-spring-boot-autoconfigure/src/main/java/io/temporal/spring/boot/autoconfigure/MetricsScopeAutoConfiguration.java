/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
