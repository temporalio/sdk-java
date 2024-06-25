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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootTest(classes = ClientOnlyTest.Configuration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ClientOnlyTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Autowired WorkflowClient workflowClient;

  @Autowired ScheduleClient scheduleClient;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testClientWiring() {
    HealthCheckResponse healthCheckResponse =
        workflowClient.getWorkflowServiceStubs().healthCheck();
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, healthCheckResponse.getStatus());
  }

  @Test
  public void testScheduleClientWiring() {
    assertNotNull(scheduleClient);
    assertNotNull(scheduleClient.getHandle("test"));
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
