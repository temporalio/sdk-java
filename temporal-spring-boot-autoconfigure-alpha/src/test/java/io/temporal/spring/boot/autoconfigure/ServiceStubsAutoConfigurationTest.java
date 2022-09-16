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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.util.IOUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ServiceStubsAutoConfigurationTest {
  private static final String LOCAL_TEMPORAL_HOST = "127.0.0.1:7233";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ServiceStubsAutoConfiguration.class));

  @Test
  void serviceStubsIsLoadedForLocalEnv() {
    contextRunner
        .withPropertyValues("spring.temporal.connection.target=local")
        .run(context -> assertThatServiceStubsIsLoaded(context));
  }

  @Test
  void serviceStubsIsLoadedForRemoteEnv() {
    contextRunner
        .withPropertyValues("spring.temporal.connection.target=" + LOCAL_TEMPORAL_HOST)
        .run(context -> assertThatServiceStubsIsLoaded(context));
  }

  @Test
  void serviceStubsIsLoadedForRemoteEnvWithPKCS8Configuration() throws URISyntaxException {
    String crtChainFile = ClassLoader.getSystemResource("pkcs8-crt-chain.pem").toURI().getPath();
    String keyFile = ClassLoader.getSystemResource("pkcs8-pk.pem").toURI().getPath();
    contextRunner
        .withPropertyValues(
            "spring.temporal.connection.target=" + LOCAL_TEMPORAL_HOST,
            "spring.temporal.connection.mtls.key-file=" + keyFile,
            "spring.temporal.connection.mtls.cert-chain-file=" + crtChainFile)
        .run(context -> assertThatServiceStubsIsLoaded(context));
  }

  @Test
  void serviceStubsIsLoadedForRemoteEnvWithPKCS8ConfigurationPassedAsString() {
    String crtChain =
        IOUtils.toString(ClassLoader.getSystemResourceAsStream("pkcs8-crt-chain.pem"));
    String crtKey = IOUtils.toString(ClassLoader.getSystemResourceAsStream("pkcs8-pk.pem"));

    contextRunner
        .withPropertyValues(
            "spring.temporal.connection.target=" + LOCAL_TEMPORAL_HOST,
            "spring.temporal.connection.mtls.key=" + crtKey,
            "spring.temporal.connection.mtls.cert-chain=" + crtChain)
        .run(context -> assertThatServiceStubsIsLoaded(context));
  }

  @Test
  void serviceStubsIsLoadedForRemoteEnvWithPKCS12Configuration() throws URISyntaxException {
    String keyFile = ClassLoader.getSystemResource("pkcs12-key.pfx").toURI().getPath();

    contextRunner
        .withPropertyValues(
            "spring.temporal.connection.target=" + LOCAL_TEMPORAL_HOST,
            "spring.temporal.connection.mtls.key-file=" + keyFile)
        .run(context -> assertThatServiceStubsIsLoaded(context));
  }

  private void assertThatServiceStubsIsLoaded(AssertableApplicationContext context) {
    assertThat(context).hasNotFailed().hasBean("temporalWorkflowServiceStubs");
    WorkflowServiceStubs workflowStub = context.getBean(WorkflowServiceStubs.class);
    assertThat(workflowStub.getOptions().getTarget()).isEqualTo(LOCAL_TEMPORAL_HOST);
  }
}
