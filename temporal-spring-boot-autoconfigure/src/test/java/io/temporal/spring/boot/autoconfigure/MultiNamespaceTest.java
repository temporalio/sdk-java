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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = MultiNamespaceTest.Configuration.class)
@ActiveProfiles(profiles = "multi-namespaces")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiNamespaceTest {

  @Autowired ConfigurableApplicationContext applicationContext;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void shouldContainsNonRootRelatedBean() {
    Assertions.assertTrue(applicationContext.containsBean("ns1NamespaceTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns2NamespaceTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns1ClientTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns2ClientTemplate"));
    Assertions.assertTrue(applicationContext.containsBean("ns1WorkflowClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns2WorkflowClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns1ScheduleClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns2ScheduleClient"));
    Assertions.assertTrue(applicationContext.containsBean("ns1WorkerFactory"));
    Assertions.assertTrue(applicationContext.containsBean("ns2WorkerFactory"));
  }


  @EnableAutoConfiguration
  public static class Configuration {}
}
