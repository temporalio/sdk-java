/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Experimental;
import java.util.Optional;

@Experimental
public interface WorkflowClientInterceptor {
  /**
   * Called when workflow stub is created during creation of new workflow.
   *
   * @return decorated stub
   */
  WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next);

  /**
   * Called when workflow stub is created for a known existing execution
   *
   * @return decorated stub
   */
  WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next);

  ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next);

  /**
   * Called once during creation of WorkflowClient to create a chain of Client Interceptors
   *
   * @param next next workflow client interceptor in the chain of interceptors
   * @return new interceptor that should decorate calls to {@code next}
   */
  WorkflowClientCallsInterceptor initWorkflowClientInterceptor(WorkflowClientCallsInterceptor next);
}
