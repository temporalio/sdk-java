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

package io.temporal.internal.external;

import io.temporal.common.v1.WorkflowExecution;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StartWorkflowExecutionParameters;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.TerminateWorkflowExecutionRequest;

public interface GenericWorkflowClientExternal {

  WorkflowExecution startWorkflow(StartWorkflowExecutionParameters startParameters);

  void signalWorkflowExecution(SignalWorkflowExecutionRequest request);

  WorkflowExecution signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionParameters parameters);

  void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest parameters);

  QueryWorkflowResponse queryWorkflow(QueryWorkflowParameters queryParameters);

  void terminateWorkflowExecution(TerminateWorkflowExecutionRequest request);

  String generateUniqueId();

  WorkflowServiceStubs getService();

  String getNamespace();
}
