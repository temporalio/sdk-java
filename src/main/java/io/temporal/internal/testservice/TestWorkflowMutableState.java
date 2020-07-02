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

package io.temporal.internal.testservice;

import io.temporal.common.v1.Payloads;
import io.temporal.decision.v1.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.enums.v1.WorkflowExecutionStatus;
import io.temporal.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.workflowservice.v1.PollForActivityTaskRequest;
import io.temporal.workflowservice.v1.PollForActivityTaskResponseOrBuilder;
import io.temporal.workflowservice.v1.PollForDecisionTaskRequest;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import io.temporal.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCanceledByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskFailedByIdRequest;
import io.temporal.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.workflowservice.v1.RespondDecisionTaskCompletedRequest;
import io.temporal.workflowservice.v1.RespondDecisionTaskFailedRequest;
import io.temporal.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.workflowservice.v1.StartWorkflowExecutionRequest;
import java.util.Optional;

interface TestWorkflowMutableState {

  ExecutionId getExecutionId();

  WorkflowExecutionStatus getWorkflowExecutionStatus();

  StartWorkflowExecutionRequest getStartRequest();

  void startDecisionTask(
      PollForDecisionTaskResponse.Builder task, PollForDecisionTaskRequest pollRequest);

  void completeDecisionTask(int historySize, RespondDecisionTaskCompletedRequest request);

  void reportCancelRequested(ExternalWorkflowExecutionCancelRequestedEventAttributes a);

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

  void startActivityTask(
      PollForActivityTaskResponseOrBuilder task, PollForActivityTaskRequest pollRequest);

  void completeActivityTask(long scheduledEventId, RespondActivityTaskCompletedRequest request);

  void completeActivityTaskById(String activityId, RespondActivityTaskCompletedByIdRequest request);

  void failActivityTask(long scheduledEventId, RespondActivityTaskFailedRequest request);

  void failActivityTaskById(String id, RespondActivityTaskFailedByIdRequest failRequest);

  /** @return is cancel requested? */
  boolean heartbeatActivityTask(long scheduledEventId, Payloads details);

  boolean heartbeatActivityTaskById(String id, Payloads details);

  void signal(SignalWorkflowExecutionRequest signalRequest);

  void signalFromWorkflow(SignalExternalWorkflowExecutionDecisionAttributes a);

  void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      Optional<TestWorkflowMutableStateImpl.CancelExternalWorkflowExecutionCallerInfo> callerInfo);

  void cancelActivityTask(
      long scheduledEventId, RespondActivityTaskCanceledRequest canceledRequest);

  void cancelActivityTaskById(String id, RespondActivityTaskCanceledByIdRequest canceledRequest);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long deadline);

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();

  Optional<TestWorkflowMutableState> getParent();

  boolean isTerminalState();
}
