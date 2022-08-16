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
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.autoconfigure.properties.ClientProperties;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClientTemplate {
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final @Nonnull WorkflowServiceStubs workflowServiceStubs;
  private final @Nullable Tracer tracer;
  private final @Nullable ClientProperties clientProperties;
  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironment testWorkflowEnvironment;

  private WorkflowClient workflowClient;

  public ClientTemplate(
      @Nonnull NamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironment testWorkflowEnvironment) {
    this.namespaceProperties = namespaceProperties;
    this.workflowServiceStubs = workflowServiceStubs;
    this.tracer = tracer;
    this.clientProperties = namespaceProperties.getClient();
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public WorkflowClient getWorkflowClient() {
    if (workflowClient == null) {
      this.workflowClient = createWorkflowClient();
    }
    return workflowClient;
  }

  private WorkflowClient createWorkflowClient() {
    if (testWorkflowEnvironment != null) {
      // TODO we should still respect the client properties here.
      // Instead of overriding, we should allow the test environment to configure the client.
      return testWorkflowEnvironment.getWorkflowClient();
    }

    WorkflowClientOptions.Builder clientOptionsBuilder = WorkflowClientOptions.newBuilder();

    if (namespaceProperties.getNamespace() != null) {
      clientOptionsBuilder.setNamespace(namespaceProperties.getNamespace());
    }

    if (tracer != null) {
      OpenTracingClientInterceptor openTracingClientInterceptor =
          new OpenTracingClientInterceptor(
              OpenTracingOptions.newBuilder().setTracer(tracer).build());
      clientOptionsBuilder.setInterceptors(openTracingClientInterceptor);
    }

    return WorkflowClient.newInstance(
        workflowServiceStubs, clientOptionsBuilder.validateAndBuildWithDefaults());
  }
}
