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
import com.uber.m3.tally.Scope;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.ConnectionProperties;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.NonRootNamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.ServiceStubsTemplate;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class NonRootBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware {

  private static final Logger log = LoggerFactory.getLogger(NonRootBeanPostProcessor.class);

  /** link {@code *Options.Builder} to customize */
  private static final String OPTIONS_BUILDER_SUFFIX = "Options.Builder";

  private static final String CUSTOMIZER_SUFFIX = "Customizer";

  private ConfigurableListableBeanFactory beanFactory;

  private final @Nonnull TemporalProperties temporalProperties;
  private final @Nullable List<NonRootNamespaceProperties> namespaceProperties;
  private final @Nullable Tracer tracer;
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;
  private final @Nullable Scope metricsScope;

  public NonRootBeanPostProcessor(
      @Nonnull TemporalProperties temporalProperties,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable Scope metricsScope) {
    this.temporalProperties = temporalProperties;
    this.namespaceProperties = temporalProperties.getNamespaces();
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
    this.metricsScope = metricsScope;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof NamespaceTemplate && beanName.equals("temporalRootNamespaceTemplate")) {
      if (namespaceProperties != null) {
        namespaceProperties.forEach(this::injectBeanByNonRootNamespace);
      }
    }
    return bean;
  }

  private void injectBeanByNonRootNamespace(NonRootNamespaceProperties ns) {
    String beanPrefix = MoreObjects.firstNonNull(ns.getAlias(), ns.getNamespace());
    DataConverter dataConverterByNamespace = findBeanByNamespace(beanPrefix, DataConverter.class);

    // found regarding namespace customizer bean, it can be optional
    TemporalOptionsCustomizer<Builder> workFactoryCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, Builder.class);
    TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> workflowServiceStubsCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(
            beanPrefix, WorkflowServiceStubsOptions.Builder.class);
    TemporalOptionsCustomizer<WorkerOptions.Builder> WorkerCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkerOptions.Builder.class);

    TemporalOptionsCustomizer<WorkflowClientOptions.Builder> workflowClientCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, WorkflowClientOptions.Builder.class);
    TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleClientCustomizer =
        findBeanByNameSpaceForTemporalCustomizer(beanPrefix, ScheduleClientOptions.Builder.class);
    TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        workflowImplementationCustomizer =
            findBeanByNameSpaceForTemporalCustomizer(
                beanPrefix, WorkflowImplementationOptions.Builder.class);

    // it not set namespace connection properties, use root connection properties
    ConnectionProperties connectionProperties =
        MoreObjects.firstNonNull(ns.getConnection(), temporalProperties.getConnection());
    ServiceStubsTemplate serviceStubsTemplate =
        new ServiceStubsTemplate(
            connectionProperties,
            metricsScope,
            testWorkflowEnvironment,
            workflowServiceStubsCustomizer);
    WorkflowServiceStubs workflowServiceStubs = serviceStubsTemplate.getWorkflowServiceStubs();

    NonRootNamespaceTemplate namespaceTemplate =
        new NonRootNamespaceTemplate(
            beanFactory,
            ns,
            workflowServiceStubs,
            dataConverterByNamespace,
            tracer,
            testWorkflowEnvironment,
            workFactoryCustomizer,
            WorkerCustomizer,
            builder ->
                // Must make sure the namespace is set at the end of the builder chain
                Optional.ofNullable(workflowClientCustomizer)
                    .map(c -> c.customize(builder))
                    .orElse(builder)
                    .setNamespace(ns.getNamespace()),
            scheduleClientCustomizer,
            workflowImplementationCustomizer);

    ClientTemplate clientTemplate = namespaceTemplate.getClientTemplate();
    WorkflowClient workflowClient = clientTemplate.getWorkflowClient();
    ScheduleClient scheduleClient = clientTemplate.getScheduleClient();
    WorkersTemplate workersTemplate = namespaceTemplate.getWorkersTemplate();
    WorkerFactory workerFactory = workersTemplate.getWorkerFactory();

    // register beans by namespace
    beanFactory.registerSingleton(
        beanPrefix + ServiceStubsTemplate.class.getSimpleName(), serviceStubsTemplate);
    beanFactory.registerSingleton(
        beanPrefix + WorkflowServiceStubs.class.getSimpleName(),
        workflowServiceStubs);
    beanFactory.registerSingleton(
        beanPrefix + NamespaceTemplate.class.getSimpleName(), namespaceTemplate);
    beanFactory.registerSingleton(
        beanPrefix + ClientTemplate.class.getSimpleName(), namespaceTemplate.getClientTemplate());
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

  private <T> T findBeanByNamespace(String beanPrefix, Class<T> clazz) {
    try {
      return beanFactory.getBean(beanPrefix + clazz.getSimpleName(), clazz);
    } catch (NoSuchBeanDefinitionException ignore) {
      // Made non-namespace bean optional
    }
    return null;
  }

  private <T> TemporalOptionsCustomizer<T> findBeanByNameSpaceForTemporalCustomizer(
      String beanPrefix, Class<T> genericOptionsBuilderClass) {
    String builderCanonicalName = genericOptionsBuilderClass.getCanonicalName();
    String bindingCustomizerName =
        builderCanonicalName.replace(OPTIONS_BUILDER_SUFFIX, CUSTOMIZER_SUFFIX);
    bindingCustomizerName =
        bindingCustomizerName.substring(bindingCustomizerName.lastIndexOf(".") + 1);

    try {
      TemporalOptionsCustomizer genericOptionsCustomizer =
          beanFactory.getBean(beanPrefix + bindingCustomizerName, TemporalOptionsCustomizer.class);
      return (TemporalOptionsCustomizer<T>) genericOptionsCustomizer;
    } catch (BeansException e) {
      log.warn("No TemporalOptionsCustomizer found for {}. ", builderCanonicalName);
      if (genericOptionsBuilderClass.isAssignableFrom(Builder.class)) {
//        print tips once
        log.info(
            "No TemporalOptionsCustomizer found for {}. \n You can add Customizer bean to do customization. \n "
                + "Note: bean name should start with namespace name and end with Customizer, and the middle part should be the customizer "
                + "target class name. \n "
                + "Example: @Bean(\"namespaceNameWorkerFactoryCustomizer\") is a customizer bean for WorkerFactory via "
                + "TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>",
            genericOptionsBuilderClass.getSimpleName());
      }
      return null;
    }
  }
}
