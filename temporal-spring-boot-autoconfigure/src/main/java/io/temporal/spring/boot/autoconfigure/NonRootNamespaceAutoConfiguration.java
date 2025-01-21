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
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredActivityInfo;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredInfo;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredWorkflowInfo;
import io.temporal.worker.WorkerFactoryOptions.Builder;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter({RootNamespaceAutoConfiguration.class, ServiceStubsAutoConfiguration.class})
@ConditionalOnBean(ServiceStubsAutoConfiguration.class)
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class NonRootNamespaceAutoConfiguration {

  protected static final Logger log =
      LoggerFactory.getLogger(NonRootNamespaceAutoConfiguration.class);

  @Bean
  public NonRootBeanPostProcessor nonRootBeanPostProcessor(
      TemporalProperties properties,
      WorkflowServiceStubs workflowServiceStubs,
      @Autowired List<DataConverter> dataConverters,
      @Autowired(required = false) @Nullable Tracer otTracer,
      @Qualifier("temporalTestWorkflowEnvironmentAdapter") @Autowired(required = false) @Nullable
          TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<Builder> workerFactoryCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer,
      @Autowired(required = false) @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    return new NonRootBeanPostProcessor(
        properties,
        properties.getNamespaces(),
        workflowServiceStubs,
        dataConverters,
        otTracer,
        testWorkflowEnvironment,
        workerFactoryCustomizer,
        workerCustomizer,
        clientCustomizer,
        scheduleCustomizer,
        workflowImplementationCustomizer);
  }

  @Bean
  public NonRootNamespaceEventListener nonRootNamespaceEventListener(
      TemporalProperties temporalProperties, @Lazy List<WorkersTemplate> workersTemplates) {
    return new NonRootNamespaceEventListener(temporalProperties, workersTemplates);
  }

  public static class NonRootNamespaceEventListener
      implements ApplicationListener<ApplicationContextEvent>, ApplicationContextAware {

    private final TemporalProperties temporalProperties;
    private final List<WorkersTemplate> workersTemplates;
    private ApplicationContext applicationContext;

    public NonRootNamespaceEventListener(
        TemporalProperties temporalProperties, List<WorkersTemplate> workersTemplates) {
      this.temporalProperties = temporalProperties;
      this.workersTemplates = workersTemplates;
    }

    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
      if (event.getApplicationContext() == this.applicationContext) {
        if (event instanceof ContextRefreshedEvent) {
          onStart();
        }
      } else if (event instanceof ContextClosedEvent) {
        onStop();
      }
    }

    private void onStart() {
      if (temporalProperties.getNamespaces() != null) {
        this.startWorkers();
      }
    }

    private void onStop() {
      workersTemplates.stream()
          .filter(WorkersTemplate::isNonRootTemplate)
          .forEach(
              workersTemplate -> {
                log.info("shutdown workers for non-root namespace");
                workersTemplate.getWorkerFactory().shutdown();
              });
    }

    private void startWorkers() {
      workersTemplates.stream()
          .filter(WorkersTemplate::isNonRootTemplate)
          .forEach(
              workersTemplate -> {
                for (Entry<String, RegisteredInfo> entry :
                    workersTemplate.getRegisteredInfo().entrySet()) {
                  String workerQueue = entry.getKey();
                  RegisteredInfo info = entry.getValue();
                  info.getRegisteredActivityInfo().stream()
                      .map(RegisteredActivityInfo::getClassName)
                      .forEach(
                          activityType ->
                              log.debug(
                                  "register activity :[{}}] in worker queue [{}]",
                                  activityType,
                                  workerQueue));
                  info.getRegisteredWorkflowInfo().stream()
                      .map(RegisteredWorkflowInfo::getClassName)
                      .forEach(
                          workflowType ->
                              log.debug(
                                  "register workflow :[{}}] in worker queue [{}]",
                                  workflowType,
                                  workerQueue));
                }
                log.info("start workers for non-root namespace");
                workersTemplate.getWorkerFactory().start();
              });
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
      this.applicationContext = applicationContext;
    }
  }
}
