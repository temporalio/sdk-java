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

package io.temporal.internal.testservice;

import io.temporal.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.PollForActivityTaskRequest;
import io.temporal.PollForActivityTaskResponse;
import io.temporal.PollForDecisionTaskRequest;
import io.temporal.PollForDecisionTaskResponse;
import io.temporal.QueryWorkflowRequest;
import io.temporal.QueryWorkflowResponse;
import io.temporal.RecordActivityTaskHeartbeatResponse;
import io.temporal.RequestCancelWorkflowExecutionRequest;
import io.temporal.RespondActivityTaskCanceledByIDRequest;
import io.temporal.RespondActivityTaskCanceledRequest;
import io.temporal.RespondActivityTaskCompletedByIDRequest;
import io.temporal.RespondActivityTaskCompletedRequest;
import io.temporal.RespondActivityTaskFailedByIDRequest;
import io.temporal.RespondActivityTaskFailedRequest;
import io.temporal.RespondDecisionTaskCompletedRequest;
import io.temporal.RespondDecisionTaskFailedRequest;
import io.temporal.RespondQueryTaskCompletedRequest;
import io.temporal.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.SignalWorkflowExecutionRequest;
import io.temporal.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.StartWorkflowExecutionRequest;
import io.temporal.StickyExecutionAttributes;
import io.temporal.WorkflowExecutionCloseStatus;
import io.temporal.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import java.util.Optional;

interface TestWorkflowMutableState {

  ExecutionId getExecutionId();

  /** @return close status of the workflow or empty if still open */
  Optional<WorkflowExecutionCloseStatus> getCloseStatus();

  StartWorkflowExecutionRequest getStartRequest();

  void startDecisionTask(PollForDecisionTaskResponse task, PollForDecisionTaskRequest pollRequest);

  void completeDecisionTask(int historySize, RespondDecisionTaskCompletedRequest request);

  void completeSignalExternalWorkflowExecution(String signalId, String runId);

  void failSignalExternalWorkflowExecution(
      String signalId, SignalExternalWorkflowExecutionFailedCause cause);

  void failDecisionTask(RespondDecisionTaskFailedRequest request);

  void childWorkflowStarted(ChildWorkflowExecutionStartedEventAttributes a);

  void childWorkflowFailed(String workflowId, ChildWorkflowExecutionFailedEventAttributes a);

  void childWorkflowTimedOut(String activityId, ChildWorkflowExecutionTimedOutEventAttributes a);

  void failStartChildWorkflow(
      String workflowId, StartChildWorkflowExecutionFailedEventAttributes a);

  void childWorkflowCompleted(String workflowId, ChildWorkflowExecutionCompletedEventAttributes a);

  void childWorkflowCanceled(String workflowId, ChildWorkflowExecutionCanceledEventAttributes a);

  void startWorkflow(
      boolean continuedAsNew, Optional<SignalWorkflowExecutionRequest> signalWithStartSignal);

  void startActivityTask(PollForActivityTaskResponse task, PollForActivityTaskRequest pollRequest);

  void completeActivityTask(String activityId, RespondActivityTaskCompletedRequest request);

  void completeActivityTaskById(String activityId, RespondActivityTaskCompletedByIDRequest request);

  void failActivityTask(String activityId, RespondActivityTaskFailedRequest request);

  void failActivityTaskById(String id, RespondActivityTaskFailedByIDRequest failRequest);

  RecordActivityTaskHeartbeatResponse heartbeatActivityTask(String activityId, byte[] details);

  void signal(SignalWorkflowExecutionRequest signalRequest);

  void signalFromWorkflow(SignalExternalWorkflowExecutionDecisionAttributes a);

  void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest);

  void cancelActivityTask(String id, RespondActivityTaskCanceledRequest canceledRequest);

  void cancelActivityTaskById(String id, RespondActivityTaskCanceledByIDRequest canceledRequest);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest);

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();
}
