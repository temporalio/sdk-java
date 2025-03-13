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

import io.temporal.authorization.AuthorizationGrpcMetadataProvider;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = ApiKeyAuthTest.Configuration.class)
@ActiveProfiles(profiles = "api-key-auth")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ApiKeyAuthTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TemporalProperties temporalProperties;
  @Autowired WorkflowClient workflowClient;
  @Autowired WorkflowServiceStubs workflowServiceStubs;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  public void testProperties() {
    Assertions.assertEquals("my-api-key", temporalProperties.getConnection().getApiKey());
    Assertions.assertEquals(1, workflowServiceStubs.getOptions().getGrpcMetadataProviders().size());
    Assertions.assertTrue(
        workflowServiceStubs.getOptions().getGrpcMetadataProviders().stream()
            .allMatch(
                provider ->
                    provider
                        .getMetadata()
                        .get(AuthorizationGrpcMetadataProvider.AUTHORIZATION_HEADER_KEY)
                        .equals("Bearer my-api-key")));
    Assertions.assertTrue(workflowServiceStubs.getOptions().getEnableHttps());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
