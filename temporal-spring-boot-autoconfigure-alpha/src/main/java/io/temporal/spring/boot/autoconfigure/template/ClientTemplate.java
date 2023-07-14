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

import com.google.common.base.Preconditions;
import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClientTemplate {
  private final @Nonnull WorkflowClientOptionsTemplate optionsTemplate;
  private final @Nullable WorkflowServiceStubs workflowServiceStubs;
  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment;

  private WorkflowClient workflowClient;
  private ScheduleClient scheduleClient;

  public ClientTemplate(
      @Nonnull String namespace,
      @Nullable DataConverter dataConverter,
      @Nullable Tracer tracer,
      @Nullable WorkflowServiceStubs workflowServiceStubs,
      @Nullable TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Nullable TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer,
      @Nullable TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomer) {
    this.optionsTemplate =
        new WorkflowClientOptionsTemplate(
            namespace, dataConverter, tracer, clientCustomizer, scheduleCustomer);
    this.workflowServiceStubs = workflowServiceStubs;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public WorkflowClient getWorkflowClient() {
    if (workflowClient == null) {
      this.workflowClient = createWorkflowClient();
    }
    return workflowClient;
  }

  public ScheduleClient getScheduleClient() {
    if (scheduleClient == null) {
      this.scheduleClient = createScheduleClient();
    }
    return scheduleClient;
  }

  private WorkflowClient createWorkflowClient() {
    if (testWorkflowEnvironment != null) {
      return testWorkflowEnvironment.getWorkflowClient();
    } else {
      Preconditions.checkState(
          workflowServiceStubs != null, "ClientTemplate was created without workflowServiceStubs");
      return WorkflowClient.newInstance(
          workflowServiceStubs, optionsTemplate.createWorkflowClientOptions());
    }
  }

  private ScheduleClient createScheduleClient() {
    if (testWorkflowEnvironment != null) {
      return ScheduleClient.newInstance(
          testWorkflowEnvironment.getWorkflowClient().getWorkflowServiceStubs());
    } else {
      Preconditions.checkState(
          workflowServiceStubs != null, "ClientTemplate was created without workflowServiceStubs");
      return ScheduleClient.newInstance(
          workflowServiceStubs, optionsTemplate.createScheduleClientOptions());
    }
  }
}
