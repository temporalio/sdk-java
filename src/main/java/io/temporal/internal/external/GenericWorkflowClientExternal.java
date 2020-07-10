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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.serviceclient.WorkflowServiceStubs;

public interface GenericWorkflowClientExternal {

  WorkflowExecution start(StartWorkflowExecutionRequest request);

  void signal(SignalWorkflowExecutionRequest request);

  WorkflowExecution signalWithStart(SignalWithStartWorkflowExecutionParameters parameters);

  void requestCancel(RequestCancelWorkflowExecutionRequest parameters);

  QueryWorkflowResponse query(QueryWorkflowRequest queryParameters);

  void terminate(TerminateWorkflowExecutionRequest request);

  String generateUniqueId();

  WorkflowServiceStubs getService();

  String getNamespace();
}
