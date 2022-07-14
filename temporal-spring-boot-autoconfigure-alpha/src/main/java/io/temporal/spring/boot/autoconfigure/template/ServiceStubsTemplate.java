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

import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.autoconfigure.properties.ServiceStubProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ServiceStubsTemplate {
  private final @Nonnull ServiceStubProperties serviceStubProperties;
  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironment testWorkflowEnvironment;

  private WorkflowServiceStubs workflowServiceStubs;

  public ServiceStubsTemplate(
      @Nonnull ServiceStubProperties serviceStubProperties,
      @Nullable TestWorkflowEnvironment testWorkflowEnvironment) {
    this.serviceStubProperties = serviceStubProperties;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public WorkflowServiceStubs getWorkflowServiceStubs() {
    if (workflowServiceStubs == null) {
      this.workflowServiceStubs = createServiceStubs();
    }
    return workflowServiceStubs;
  }

  private WorkflowServiceStubs createServiceStubs() {
    WorkflowServiceStubs workflowServiceStubs;
    if (testWorkflowEnvironment != null) {
      workflowServiceStubs = testWorkflowEnvironment.getWorkflowClient().getWorkflowServiceStubs();
    } else {
      switch (serviceStubProperties.getTarget().toLowerCase()) {
        case ServiceStubProperties.TARGET_LOCAL_SERVICE:
          workflowServiceStubs = WorkflowServiceStubs.newLocalServiceStubs();
          break;
        default:
          WorkflowServiceStubsOptions.Builder stubsOptionsBuilder =
              WorkflowServiceStubsOptions.newBuilder();

          if (serviceStubProperties.getTarget() != null) {
            stubsOptionsBuilder.setTarget(serviceStubProperties.getTarget());
          }

          workflowServiceStubs =
              WorkflowServiceStubs.newServiceStubs(
                  stubsOptionsBuilder.validateAndBuildWithDefaults());
      }
    }

    return workflowServiceStubs;
  }
}
