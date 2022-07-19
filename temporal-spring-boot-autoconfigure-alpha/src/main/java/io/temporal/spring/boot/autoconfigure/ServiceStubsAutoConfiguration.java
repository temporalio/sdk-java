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

import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ServiceStubsTemplate;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter(TestServerAutoConfiguration.class)
@ConditionalOnExpression(
    "${spring.temporal.testServer.enabled:false} || '${spring.temporal.serviceStubs.target:}'.length() > 0")
@Order(2)
public class ServiceStubsAutoConfiguration {
  @Bean(name = "temporalServiceStubsTemplate")
  public ServiceStubsTemplate serviceStubsTemplate(
      TemporalProperties properties,
      @Qualifier("temporalTestWorkflowEnvironment") @Autowired(required = false) @Nullable
          TestWorkflowEnvironment testWorkflowEnvironment) {
    return new ServiceStubsTemplate(properties.getServiceStubs(), testWorkflowEnvironment);
  }

  @Bean(name = "temporalWorkflowServiceStubs")
  public WorkflowServiceStubs workflowServiceStubsTemplate(
      @Qualifier("temporalServiceStubsTemplate") ServiceStubsTemplate serviceStubsTemplate) {
    return serviceStubsTemplate.getWorkflowServiceStubs();
  }
}
