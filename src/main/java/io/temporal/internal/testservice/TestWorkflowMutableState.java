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

import com.google.protobuf.ByteString;
import io.temporal.internal.testservice.TestWorkflowMutableStateImpl.QueryId;
import io.temporal.proto.common.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.common.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.common.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.common.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.common.StickyExecutionAttributes;
import io.temporal.proto.enums.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.proto.enums.WorkflowExecutionCloseStatus;
import io.temporal.proto.workflowservice.PollForActivityTaskRequest;
import io.temporal.proto.workflowservice.PollForActivityTaskResponseOrBuilder;
import io.temporal.proto.workflowservice.PollForDecisionTaskRequest;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.QueryWorkflowRequest;
import io.temporal.proto.workflowservice.QueryWorkflowResponse;
import io.temporal.proto.workflowservice.RequestCancelWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedByIDRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskCompletedRequest;
import io.temporal.proto.workflowservice.RespondDecisionTaskFailedRequest;
import io.temporal.proto.workflowservice.RespondQueryTaskCompletedRequest;
import io.temporal.proto.workflowservice.SignalWorkflowExecutionRequest;
import io.temporal.proto.workflowservice.StartWorkflowExecutionRequest;
import java.util.Optional;

interface TestWorkflowMutableState {

  ExecutionId getExecutionId();

  /** @return close status of the workflow or empty if still open */
  Optional<WorkflowExecutionCloseStatus> getCloseStatus();

  StartWorkflowExecutionRequest getStartRequest();

  void startDecisionTask(
      PollForDecisionTaskResponse.Builder task, PollForDecisionTaskRequest pollRequest);

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

  void startActivityTask(
      PollForActivityTaskResponseOrBuilder task, PollForActivityTaskRequest pollRequest);

  void completeActivityTask(String activityId, RespondActivityTaskCompletedRequest request);

  void completeActivityTaskById(String activityId, RespondActivityTaskCompletedByIDRequest request);

  void failActivityTask(String activityId, RespondActivityTaskFailedRequest request);

  void failActivityTaskById(String id, RespondActivityTaskFailedByIDRequest failRequest);

  /** @return is cancel requested? */
  boolean heartbeatActivityTask(String activityId, ByteString details);

  void signal(SignalWorkflowExecutionRequest signalRequest);

  void signalFromWorkflow(SignalExternalWorkflowExecutionDecisionAttributes a);

  void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest);

  void cancelActivityTask(String id, RespondActivityTaskCanceledRequest canceledRequest);

  void cancelActivityTaskById(String id, RespondActivityTaskCanceledByIDRequest canceledRequest);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest);

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();

  Optional<TestWorkflowMutableState> getParent();
}
