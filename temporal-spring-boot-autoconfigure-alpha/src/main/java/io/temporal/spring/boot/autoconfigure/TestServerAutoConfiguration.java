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

import com.uber.m3.tally.Scope;
import io.opentracing.Tracer;
import io.temporal.common.converter.DataConverter;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.List;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a client based on `spring.temporal.testServer` section */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnClass(name = "io.temporal.testing.TestWorkflowEnvironment")
@ConditionalOnProperty(
    prefix = "spring.temporal",
    name = "test-server.enabled",
    havingValue = "true")
@AutoConfigureAfter({OpenTracingAutoConfiguration.class, MetricsScopeAutoConfiguration.class})
public class TestServerAutoConfiguration {
  @Bean(name = "temporalTestWorkflowEnvironmentAdapter")
  public TestWorkflowEnvironmentAdapter testTestWorkflowEnvironmentAdapter(
      @Qualifier("temporalTestWorkflowEnvironment")
          TestWorkflowEnvironment testWorkflowEnvironment) {
    return new TestWorkflowEnvironmentAdapterImpl(testWorkflowEnvironment);
  }

  @Bean(name = "temporalTestWorkflowEnvironment", destroyMethod = "close")
  public TestWorkflowEnvironment testWorkflowEnvironment(
      TemporalProperties properties,
      @Qualifier("temporalMetricsScope") @Autowired(required = false) @Nullable Scope metricsScope,
      @Autowired List<DataConverter> dataConverters,
      @Qualifier("mainDataConverter") @Autowired(required = false) @Nullable
          DataConverter mainDataConverter,
      @Autowired(required = false) @Nullable Tracer otTracer) {
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.choseDataConverter(dataConverters, mainDataConverter);
    ClientTemplate clientTemplate =
        new ClientTemplate(properties.getNamespace(), chosenDataConverter, otTracer, null, null);

    TestEnvironmentOptions.Builder options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(clientTemplate.getWorkflowClientOptions());
    if (metricsScope != null) {
      options.setMetricsScope(metricsScope);
    }

    return TestWorkflowEnvironment.newInstance(options.build());
  }
}
