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
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NamespaceTemplate {
  private final @Nonnull TemporalProperties properties;
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final @Nonnull WorkflowServiceStubs workflowServiceStubs;
  private final @Nullable Tracer tracer;
  private final @Nullable TestWorkflowEnvironment testWorkflowEnvironment;

  private ClientTemplate clientTemplate;
  private WorkersTemplate workersTemplate;

  public NamespaceTemplate(
      @Nonnull TemporalProperties properties,
      @Nonnull NamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable Tracer tracer,
      @Nullable TestWorkflowEnvironment testWorkflowEnvironment) {
    this.properties = properties;
    this.namespaceProperties = namespaceProperties;
    this.workflowServiceStubs = workflowServiceStubs;
    this.tracer = tracer;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public ClientTemplate getClientTemplate() {
    if (clientTemplate == null) {
      this.clientTemplate =
          new ClientTemplate(
              namespaceProperties, workflowServiceStubs, tracer, testWorkflowEnvironment);
    }
    return clientTemplate;
  }

  public WorkersTemplate getWorkersTemplate() {
    if (workersTemplate == null) {
      this.workersTemplate =
          new WorkersTemplate(
              properties,
              namespaceProperties,
              workflowServiceStubs,
              tracer,
              testWorkflowEnvironment);
    }
    return this.workersTemplate;
  }
}
