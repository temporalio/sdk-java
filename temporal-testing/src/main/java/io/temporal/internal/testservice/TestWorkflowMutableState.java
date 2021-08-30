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

import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.RequestCancelWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedByIdRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.api.workflowservice.v1.RespondQueryTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import java.util.Optional;

interface TestWorkflowMutableState {

  ExecutionId getExecutionId();

  WorkflowExecutionStatus getWorkflowExecutionStatus();

  StartWorkflowExecutionRequest getStartRequest();

  void startWorkflowTask(
      PollWorkflowTaskQueueResponse.Builder task, PollWorkflowTaskQueueRequest pollRequest);

  void completeWorkflowTask(int historySize, RespondWorkflowTaskCompletedRequest request);

  void reportCancelRequested(ExternalWorkflowExecutionCancelRequestedEventAttributes a);

  void completeSignalExternalWorkflowExecution(String signalId, String runId);

  void failSignalExternalWorkflowExecution(
      String signalId, SignalExternalWorkflowExecutionFailedCause cause);

  void failWorkflowTask(RespondWorkflowTaskFailedRequest request);

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
      PollActivityTaskQueueResponseOrBuilder task, PollActivityTaskQueueRequest pollRequest);

  void completeActivityTask(long scheduledEventId, RespondActivityTaskCompletedRequest request);

  void completeActivityTaskById(String activityId, RespondActivityTaskCompletedByIdRequest request);

  void failActivityTask(long scheduledEventId, RespondActivityTaskFailedRequest request);

  void failActivityTaskById(String id, RespondActivityTaskFailedByIdRequest failRequest);

  /** @return is cancel requested? */
  boolean heartbeatActivityTask(long scheduledEventId, Payloads details);

  boolean heartbeatActivityTaskById(String id, Payloads details);

  void signal(SignalWorkflowExecutionRequest signalRequest);

  void signalFromWorkflow(SignalExternalWorkflowExecutionCommandAttributes a);

  void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      Optional<TestWorkflowMutableStateImpl.CancelExternalWorkflowExecutionCallerInfo> callerInfo);

  void terminateWorkflowExecution(TerminateWorkflowExecutionRequest request);

  void cancelActivityTask(
      long scheduledEventId, RespondActivityTaskCanceledRequest canceledRequest);

  void cancelActivityTaskById(String id, RespondActivityTaskCanceledByIdRequest canceledRequest);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long deadline);

  DescribeWorkflowExecutionResponse describeWorkflowExecution();

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();

  Optional<TestWorkflowMutableState> getParent();

  boolean isTerminalState();
}
