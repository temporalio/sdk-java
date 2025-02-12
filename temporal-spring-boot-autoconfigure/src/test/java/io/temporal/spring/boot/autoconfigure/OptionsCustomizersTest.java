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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkerOptionsCustomizer;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = OptionsCustomizersTest.Configuration.class)
@ActiveProfiles(profiles = "auto-discovery-by-task-queue")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OptionsCustomizersTest {
  @Autowired ConfigurableApplicationContext applicationContext;

  @Autowired List<TemporalOptionsCustomizer<?>> customizers;
  @Autowired WorkerOptionsCustomizer workerCustomizer;

  @BeforeEach
  void setUp() {
    applicationContext.start();
  }

  @Test
  @Timeout(value = 10)
  public void testCustomizersGotCalled() {
    assertEquals(5, customizers.size());
    customizers.forEach(c -> verify(c).customize(any()));
    verify(workerCustomizer).customize(any(), eq("UnitTest"), eq("UnitTest"));
  }

  @ComponentScan(
      excludeFilters =
          @ComponentScan.Filter(
              pattern = "io\\.temporal\\.spring\\.boot\\.autoconfigure\\.byworkername\\..*",
              type = FilterType.REGEX))
  public static class Configuration {

    @Bean
    public TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<TestEnvironmentOptions.Builder> testEnvironmentCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer() {
      return getReturningMock();
    }

    @Bean
    public TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        WorkflowImplementationCustomizer() {
      return getReturningMock();
    }

    @Bean
    public WorkerOptionsCustomizer workerCustomizer() {
      WorkerOptionsCustomizer mock = mock(WorkerOptionsCustomizer.class);
      when(mock.customize(any())).thenAnswer(invocation -> invocation.getArgument(0)).getMock();
      when(mock.customize(any(), any(), any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
          .getMock();
      return mock;
    }

    @SuppressWarnings("unchecked")
    private <T> TemporalOptionsCustomizer<T> getReturningMock() {
      return when(mock(TemporalOptionsCustomizer.class).customize(any()))
          .thenAnswer(invocation -> invocation.getArgument(0))
          .getMock();
    }
  }
}
