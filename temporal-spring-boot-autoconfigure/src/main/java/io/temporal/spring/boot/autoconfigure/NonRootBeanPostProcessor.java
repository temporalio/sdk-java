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

import com.google.common.base.MoreObjects;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.NonRootNamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions.Builder;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class NonRootBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

  private ConfigurableListableBeanFactory beanFactory;

  private final @Nullable List<NonRootNamespaceProperties> namespaceProperties;
  private final @Nonnull WorkflowServiceStubs workflowServiceStubs;
  private final @Nullable Tracer tracer;
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;
  private final @Nullable TemporalOptionsCustomizer<Builder> workerFactoryCustomizer;
  private final @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer;
  private final @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer;
  private final @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder>
      scheduleCustomizer;
  private final @Nullable TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
      workflowImplementationCustomizer;

  public NonRootBeanPostProcessor(
      @Nullable List<NonRootNamespaceProperties> namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable TemporalOptionsCustomizer<Builder> workerFactoryCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer,
      @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    this.namespaceProperties = namespaceProperties;
    this.workflowServiceStubs = workflowServiceStubs;
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
    this.workerFactoryCustomizer = workerFactoryCustomizer;
    this.workerCustomizer = workerCustomizer;
    this.clientCustomizer = clientCustomizer;
    this.scheduleCustomizer = scheduleCustomizer;
    this.workflowImplementationCustomizer = workflowImplementationCustomizer;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof NamespaceTemplate && beanName.equals("temporalRootNamespaceTemplate")) {
      if (namespaceProperties != null) {
        namespaceProperties.forEach(this::injectNonPrimaryBean);
      }
    }
    return bean;
  }

  private void injectNonPrimaryBean(NonRootNamespaceProperties ns) {
    String beanPrefix = MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace());
    DataConverter dataConverterByNamespace = null;
    try {
      dataConverterByNamespace =
          beanFactory.getBean(
              beanPrefix + DataConverter.class.getSimpleName(), DataConverter.class);
    } catch (NoSuchBeanDefinitionException ignore) {
      // Made non-namespace data converter optional
    }
    NonRootNamespaceTemplate namespaceTemplate =
        new NonRootNamespaceTemplate(
            beanFactory,
            ns,
            workflowServiceStubs,
            dataConverterByNamespace,
            tracer,
            testWorkflowEnvironment,
            workerFactoryCustomizer,
            workerCustomizer,
            builder ->
                Optional.ofNullable(clientCustomizer)
                    .map(c -> c.customize(builder))
                    .orElse(builder)
                    .setNamespace(ns.getNamespace()),
            scheduleCustomizer,
            workflowImplementationCustomizer);

    ClientTemplate clientTemplate = namespaceTemplate.getClientTemplate();
    WorkflowClient workflowClient = clientTemplate.getWorkflowClient();
    ScheduleClient scheduleClient = clientTemplate.getScheduleClient();
    WorkersTemplate workersTemplate = namespaceTemplate.getWorkersTemplate();
    WorkerFactory workerFactory = workersTemplate.getWorkerFactory();
    beanFactory.registerSingleton(
        beanPrefix + NamespaceTemplate.class.getSimpleName(), namespaceTemplate);
    beanFactory.registerSingleton(
        beanPrefix + ClientTemplate.class.getSimpleName(), clientTemplate);
    beanFactory.registerSingleton(
        beanPrefix + WorkersTemplate.class.getSimpleName(), workersTemplate);
    beanFactory.registerSingleton(
        beanPrefix + WorkflowClient.class.getSimpleName(), workflowClient);
    beanFactory.registerSingleton(
        beanPrefix + ScheduleClient.class.getSimpleName(), scheduleClient);
    beanFactory.registerSingleton(beanPrefix + WorkerFactory.class.getSimpleName(), workerFactory);
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }
}
