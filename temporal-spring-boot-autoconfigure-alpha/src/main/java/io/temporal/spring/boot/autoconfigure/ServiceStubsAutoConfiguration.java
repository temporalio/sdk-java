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
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ServiceStubsTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter(
    value = {MetricsScopeAutoConfiguration.class, TestServerAutoConfiguration.class})
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class ServiceStubsAutoConfiguration {
  @Bean(name = "temporalServiceStubsTemplate")
  public ServiceStubsTemplate serviceStubsTemplate(
      TemporalProperties properties,
      @Qualifier("temporalMetricsScope") @Autowired(required = false) @Nullable Scope metricsScope,
      @Qualifier("temporalTestWorkflowEnvironmentAdapter") @Autowired(required = false) @Nullable
          TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder>
              workflowServiceStubsCustomizer) {
    return new ServiceStubsTemplate(
        properties.getConnection(),
        metricsScope,
        testWorkflowEnvironment,
        workflowServiceStubsCustomizer);
  }

  @Bean(name = "temporalWorkflowServiceStubs")
  public WorkflowServiceStubs workflowServiceStubsTemplate(
      @Qualifier("temporalServiceStubsTemplate") ServiceStubsTemplate serviceStubsTemplate) {
    return serviceStubsTemplate.getWorkflowServiceStubs();
  }
}
