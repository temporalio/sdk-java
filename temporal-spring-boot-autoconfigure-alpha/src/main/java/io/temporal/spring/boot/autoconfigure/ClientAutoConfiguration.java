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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.autoconfigure.properties.ClientProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a client based on `spring.temporal.client` section */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnClass(name = "io.temporal.client.WorkflowClient")
@ConditionalOnProperty(prefix = "spring.temporal", name = "client.target")
public class ClientAutoConfiguration {

  @Bean(name = "temporalWorkflowClient")
  @ConditionalOnMissingBean(name = "temporalWorkflowClient")
  public WorkflowClient client(
      TemporalProperties temporalProperties,
      @Qualifier("temporalTestWorkflowEnvironment") @Autowired(required = false) @Nullable
          TestWorkflowEnvironment testWorkflowEnvironment) {
    ClientProperties clientProperties = temporalProperties.getClient();
    WorkflowServiceStubs workflowServiceStubs;
    switch (temporalProperties.getClient().getTarget().toLowerCase()) {
      case ClientProperties.TARGET_IN_PROCESS_TEST_SERVER:
        if (testWorkflowEnvironment == null) {
          throw new BeanDefinitionValidationException(
              "Test workflow environment is not available. "
                  + "Please make sure that you have enabled the 'temporal-test' module "
                  + "and configured `spring.temporal.testServer.enabled: true`.");
        }
        return testWorkflowEnvironment.getWorkflowClient();
      case ClientProperties.TARGET_LOCAL_SERVICE:
        workflowServiceStubs = WorkflowServiceStubs.newLocalServiceStubs();
        break;
      default:
        WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
            WorkflowServiceStubsOptions.newBuilder();

        if (clientProperties.getTarget() != null) {
          stubsOptionsBuilder.setTarget(clientProperties.getTarget());
        }

        workflowServiceStubs =
            WorkflowServiceStubs.newServiceStubs(
                stubsOptionsBuilder.validateAndBuildWithDefaults());
    }

    WorkflowClientOptions.Builder clientOptionsBuilder = WorkflowClientOptions.newBuilder();

    if (clientProperties.getNamespace() != null) {
      clientOptionsBuilder.setNamespace(clientProperties.getNamespace());
    }

    return WorkflowClient.newInstance(
        workflowServiceStubs, clientOptionsBuilder.validateAndBuildWithDefaults());
  }
}
