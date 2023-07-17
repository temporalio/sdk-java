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

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextStartedEvent;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter({ServiceStubsAutoConfiguration.class, OpenTracingAutoConfiguration.class})
@ConditionalOnBean(ServiceStubsAutoConfiguration.class)
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class RootNamespaceAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(RootNamespaceAutoConfiguration.class);

  private final ConfigurableListableBeanFactory beanFactory;

  public RootNamespaceAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Bean(name = "temporalRootNamespaceTemplate")
  public NamespaceTemplate rootNamespaceTemplate(
      TemporalProperties properties,
      WorkflowServiceStubs workflowServiceStubs,
      @Autowired List<DataConverter> dataConverters,
      @Qualifier("mainDataConverter") @Autowired(required = false) @Nullable
          DataConverter mainDataConverter,
      @Autowired(required = false) @Nullable Tracer otTracer,
      @Qualifier("temporalTestWorkflowEnvironmentAdapter") @Autowired(required = false) @Nullable
          TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomize) {
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.choseDataConverter(dataConverters, mainDataConverter);
    return new NamespaceTemplate(
        properties,
        properties,
        workflowServiceStubs,
        chosenDataConverter,
        otTracer,
        testWorkflowEnvironment,
        workerFactoryCustomizer,
        workerCustomizer,
        clientCustomizer,
        scheduleCustomize);
  }

  /** Client */
  @Bean(name = "temporalClientTemplate")
  public ClientTemplate clientTemplate(
      @Qualifier("temporalRootNamespaceTemplate") NamespaceTemplate rootNamespaceTemplate) {
    return rootNamespaceTemplate.getClientTemplate();
  }

  @Bean(name = "temporalWorkflowClient")
  public WorkflowClient client(ClientTemplate clientTemplate) {
    return clientTemplate.getWorkflowClient();
  }

  @Bean(name = "temporalScheduleClient")
  public ScheduleClient scheduleClient(ClientTemplate clientTemplate) {
    return clientTemplate.getScheduleClient();
  }

  /** Workers */
  @Bean(name = "temporalWorkersTemplate")
  @Conditional(WorkersPresentCondition.class)
  // add an explicit dependency on the existence of the expected client bean,
  // so we don't initialize a client that user doesn't expect
  @DependsOn("temporalClientTemplate")
  public WorkersTemplate workersTemplate(
      @Qualifier("temporalRootNamespaceTemplate") NamespaceTemplate temporalRootNamespaceTemplate) {
    return temporalRootNamespaceTemplate.getWorkersTemplate();
  }

  @Bean(name = "temporalWorkerFactory", destroyMethod = "shutdown")
  @Conditional(WorkersPresentCondition.class)
  public WorkerFactory workerFactory(
      @Qualifier("temporalWorkersTemplate") WorkersTemplate workersTemplate) {
    return workersTemplate.getWorkerFactory();
  }

  @Bean(name = "temporalWorkers")
  @Conditional(WorkersPresentCondition.class)
  public Collection<Worker> workers(
      @Qualifier("temporalWorkersTemplate") WorkersTemplate workersTemplate) {
    Collection<Worker> workers = workersTemplate.getWorkers();
    workers.forEach(
        worker -> beanFactory.registerSingleton("temporalWorker-" + worker.getTaskQueue(), worker));
    return workers;
  }

  @ConditionalOnProperty(prefix = "spring.temporal", name = "startWorkers", matchIfMissing = true)
  @Conditional(WorkersPresentCondition.class)
  @Bean
  public WorkerFactoryStarter workerFactoryStarter(WorkerFactory workerFactory) {
    return new WorkerFactoryStarter(workerFactory);
  }

  // It needs to listed on ContextStartedEvent, not ContextRefreshedEvent.
  // Using ContextRefreshedEvent will cause start of workers early, during the context
  // initialization
  // and potentially incorrect order of bean initialization.
  // Refresh event can also be fired multiple times during the context initialization.
  // For this listener to ever work, Spring context needs to be actually started.
  // Note that a lot of online samples for Spring don't start the context at all:
  // https://stackoverflow.com/questions/48099355/contextstartedevent-not-firing-in-custom-listener
  public static class WorkerFactoryStarter implements ApplicationListener<ContextStartedEvent> {
    private final WorkerFactory workerFactory;

    public WorkerFactoryStarter(WorkerFactory workerFactory) {
      this.workerFactory = workerFactory;
    }

    @Override
    public void onApplicationEvent(@Nonnull ContextStartedEvent event) {
      workerFactory.start();
    }
  }
}
