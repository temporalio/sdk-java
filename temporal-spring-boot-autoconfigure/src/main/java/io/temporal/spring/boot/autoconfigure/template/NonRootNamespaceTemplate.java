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

package io.temporal.spring.boot.autoconfigure.template;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;

public class NonRootNamespaceTemplate extends NamespaceTemplate {

  private BeanFactory beanFactory;

  public NonRootNamespaceTemplate(
      @Nonnull BeanFactory beanFactory,
      @Nonnull NonRootNamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable DataConverter dataConverter,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer,
      @Nullable
          TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
              workflowImplementationCustomizer) {
    super(
        namespaceProperties,
        workflowServiceStubs,
        dataConverter,
        tracer,
        testWorkflowEnvironment,
        workerFactoryCustomizer,
        workerCustomizer,
        clientCustomizer,
        scheduleCustomizer,
        workflowImplementationCustomizer);
    this.beanFactory = beanFactory;
  }

  @Override
  public WorkersTemplate getWorkersTemplate() {
    WorkersTemplate workersTemplate = super.getWorkersTemplate();
    workersTemplate.setEnvironment(beanFactory.getBean(Environment.class));
    workersTemplate.setBeanFactory(beanFactory);

    return workersTemplate;
  }
}
