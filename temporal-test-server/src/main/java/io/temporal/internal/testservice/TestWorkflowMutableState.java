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

package io.temporal.internal.testservice;

import io.grpc.Deadline;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.*;
import io.temporal.api.nexus.v1.StartOperationResponse;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflowservice.v1.*;
import java.util.Optional;
import javax.annotation.Nullable;

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

  @Nullable
  PollWorkflowTaskQueueResponse startWorkflow(
      boolean continuedAsNew,
      @Nullable SignalWorkflowExecutionRequest signalWithStartSignal,
      @Nullable PollWorkflowTaskQueueRequest eagerWorkflowTaskDispatchPollRequest);

  void startActivityTask(
      PollActivityTaskQueueResponseOrBuilder task, PollActivityTaskQueueRequest pollRequest);

  void completeActivityTask(long scheduledEventId, RespondActivityTaskCompletedRequest request);

  void completeActivityTaskById(String activityId, RespondActivityTaskCompletedByIdRequest request);

  void failActivityTask(long scheduledEventId, RespondActivityTaskFailedRequest request);

  void failActivityTaskById(String id, RespondActivityTaskFailedByIdRequest failRequest);

  /**
   * @return is cancel requested?
   */
  boolean heartbeatActivityTask(long scheduledEventId, Payloads details);

  boolean heartbeatActivityTaskById(String id, Payloads details, String identity);

  void signal(SignalWorkflowExecutionRequest signalRequest);

  void signalFromWorkflow(SignalExternalWorkflowExecutionCommandAttributes a);

  void requestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest,
      Optional<TestWorkflowMutableStateImpl.CancelExternalWorkflowExecutionCallerInfo> callerInfo);

  void terminateWorkflowExecution(TerminateWorkflowExecutionRequest request);

  void cancelActivityTask(
      long scheduledEventId, RespondActivityTaskCanceledRequest canceledRequest);

  void cancelActivityTaskById(String id, RespondActivityTaskCanceledByIdRequest canceledRequest);

  void startNexusOperation(
      long scheduledEventId, String clientIdentity, StartOperationResponse.Async resp);

  void cancelNexusOperation(NexusOperationRef ref);

  void completeNexusOperation(NexusOperationRef ref, Payload result);

  void failNexusOperation(NexusOperationRef ref, Failure failure);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long deadline);

  UpdateWorkflowExecutionResponse updateWorkflowExecution(
      UpdateWorkflowExecutionRequest request, Deadline deadline);

  PollWorkflowExecutionUpdateResponse pollUpdateWorkflowExecution(
      PollWorkflowExecutionUpdateRequest request, Deadline deadline);

  DescribeWorkflowExecutionResponse describeWorkflowExecution();

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();

  Optional<TestWorkflowMutableState> getParent();

  boolean isTerminalState();
}
