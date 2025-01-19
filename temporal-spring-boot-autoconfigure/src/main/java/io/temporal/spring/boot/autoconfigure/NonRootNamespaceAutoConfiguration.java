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

import com.google.protobuf.util.Durations;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.opentracing.Tracer;
import io.temporal.api.enums.v1.ArchivalState;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredActivityInfo;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredInfo;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredWorkflowInfo;
import io.temporal.worker.WorkerFactoryOptions.Builder;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
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
      @Qualifier("mainDataConverter") @Autowired(required = false) @Nullable
          DataConverter mainDataConverter,
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
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.choseDataConverter(dataConverters, mainDataConverter);
    return new NonRootBeanPostProcessor(
        properties,
        properties.getNamespaces(),
        workflowServiceStubs,
        chosenDataConverter,
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
      TemporalProperties temporalProperties,
      WorkflowServiceStubs workflowServiceStubs,
      @Lazy List<WorkersTemplate> workersTemplates) {
    return new NonRootNamespaceEventListener(
        temporalProperties, workflowServiceStubs, workersTemplates);
  }

  public static class NonRootNamespaceEventListener
      implements ApplicationListener<ApplicationContextEvent>, ApplicationContextAware {

    private final TemporalProperties temporalProperties;
    private final WorkflowServiceStubs workflowServiceStubs;
    private final List<WorkersTemplate> workersTemplates;
    private ApplicationContext applicationContext;

    public NonRootNamespaceEventListener(
        TemporalProperties temporalProperties,
        WorkflowServiceStubs workflowServiceStubs,
        List<WorkersTemplate> workersTemplates) {
      this.temporalProperties = temporalProperties;
      this.workflowServiceStubs = workflowServiceStubs;
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
        WorkflowServiceGrpc.WorkflowServiceBlockingStub workflowServiceStub =
            workflowServiceStubs.blockingStub();
        for (NonRootNamespaceProperties nonRootNamespaceProperties :
            temporalProperties.getNamespaces()) {
          if (Boolean.TRUE.equals(nonRootNamespaceProperties.getNamespaceAutoRegister())) {
            this.namespaceRegister(workflowServiceStub, nonRootNamespaceProperties);
          }
        }

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

    private void namespaceRegister(
        WorkflowServiceBlockingStub workflowServiceStub,
        NonRootNamespaceProperties nonRootNamespaceProperties) {
      String namespace = nonRootNamespaceProperties.getNamespace();
      Duration retentionPeriod =
          Optional.of(nonRootNamespaceProperties)
              .map(NonRootNamespaceProperties::getRetentionPeriod)
              .orElse(Duration.ofDays(3));
      ArchivalState historyArchivalState =
          Optional.of(nonRootNamespaceProperties)
              .map(NonRootNamespaceProperties::getHistoryArchivalState)
              .map(this::getArchivalState)
              .orElse(ArchivalState.ARCHIVAL_STATE_DISABLED);
      ArchivalState visibilityArchivalState =
          Optional.of(nonRootNamespaceProperties)
              .map(NonRootNamespaceProperties::getVisibilityArchivalState)
              .map(this::getArchivalState)
              .orElse(ArchivalState.ARCHIVAL_STATE_DISABLED);
      log.debug(
          "register namespace [{}] with retention period [{}], history archival state [{}], visibility archival state [{}]",
          namespace,
          retentionPeriod,
          historyArchivalState,
          visibilityArchivalState);

      try {
        workflowServiceStub.describeNamespace(
            DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
        log.debug("Namespace already exists: {}", namespace);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Code.NOT_FOUND) {
          workflowServiceStub.registerNamespace(
              RegisterNamespaceRequest.newBuilder()
                  .setNamespace(namespace)
                  .setWorkflowExecutionRetentionPeriod(Durations.fromDays(retentionPeriod.toDays()))
                  .setHistoryArchivalState(historyArchivalState)
                  .setVisibilityArchivalState(visibilityArchivalState)
                  .build());
          log.info("Namespace created: {}", nonRootNamespaceProperties);
        } else {
          throw e;
        }
      }
    }

    private ArchivalState getArchivalState(Boolean state) {
      if (state) {
        return ArchivalState.ARCHIVAL_STATE_ENABLED;
      } else {
        return ArchivalState.ARCHIVAL_STATE_DISABLED;
      }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
      this.applicationContext = applicationContext;
    }
  }
}
