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

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = StartWorkersTest.Configuration.class)
@ActiveProfiles(profiles = "disable-start-workers")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StartWorkersTest {

  @Autowired TemporalProperties temporalProperties;

  @Autowired TestWorkflowEnvironment testWorkflowEnvironment;

  @Test
  @Timeout(value = 10)
  public void testStartWorkersConfigDisabled() {
    assertFalse(temporalProperties.getStartWorkers());
  }

  @Test
  @Timeout(value = 10)
  public void testWorkersStarted() {
    Worker worker = testWorkflowEnvironment.getWorkerFactory().getWorker("UnitTest");
    assertNotNull(worker);
    assertTrue(worker.isSuspended());
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {}
}
