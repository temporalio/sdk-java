/*
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

import io.temporal.QueryWorkflowResponse;
import io.temporal.WorkflowExecution;
import io.temporal.internal.common.SignalWithStartWorkflowExecutionParameters;
import io.temporal.internal.common.StartWorkflowExecutionParameters;
import io.temporal.internal.common.TerminateWorkflowExecutionParameters;
import io.temporal.internal.replay.QueryWorkflowParameters;
import io.temporal.internal.replay.SignalExternalWorkflowParameters;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;

public interface GenericWorkflowClientExternal {

  WorkflowExecution startWorkflow(StartWorkflowExecutionParameters startParameters);

  void signalWorkflowExecution(SignalExternalWorkflowParameters signalParameters);

  WorkflowExecution signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionParameters parameters);

  void requestCancelWorkflowExecution(WorkflowExecution execution);

  QueryWorkflowResponse queryWorkflow(QueryWorkflowParameters queryParameters);

  void terminateWorkflowExecution(TerminateWorkflowExecutionParameters terminateParameters);

  String generateUniqueId();

  GrpcWorkflowServiceFactory getService();

  String getDomain();
}
