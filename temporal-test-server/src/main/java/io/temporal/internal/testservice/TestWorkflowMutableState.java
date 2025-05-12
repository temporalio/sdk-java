package io.temporal.internal.testservice;

import io.grpc.Deadline;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.SignalExternalWorkflowExecutionFailedCause;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.*;
import io.temporal.api.nexus.v1.Link;
import io.temporal.api.nexus.v1.StartOperationResponse;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflow.v1.RequestIdInfo;
import io.temporal.api.workflowservice.v1.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

interface TestWorkflowMutableState {

  ExecutionId getExecutionId();

  WorkflowExecutionStatus getWorkflowExecutionStatus();

  StartWorkflowExecutionRequest getStartRequest();

  void startWorkflowTask(
      PollWorkflowTaskQueueResponse.Builder task, PollWorkflowTaskQueueRequest pollRequest);

  void completeWorkflowTask(int historySize, RespondWorkflowTaskCompletedRequest request);

  void applyOnConflictOptions(StartWorkflowExecutionRequest request);

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
      @Nullable PollWorkflowTaskQueueRequest eagerWorkflowTaskDispatchPollRequest,
      @Nullable Consumer<TestWorkflowMutableState> withStart);

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

  void cancelNexusOperation(NexusOperationRef ref, Failure failure);

  void cancelNexusOperationRequestAcknowledge(NexusOperationRef ref);

  void completeNexusOperation(NexusOperationRef ref, Payload result);

  void completeAsyncNexusOperation(
      NexusOperationRef ref, Payload result, String operationID, Link startLink);

  void failNexusOperation(NexusOperationRef ref, Failure failure);

  boolean validateOperationTaskToken(NexusTaskToken tt);

  QueryWorkflowResponse query(QueryWorkflowRequest queryRequest, long deadline);

  TestWorkflowMutableStateImpl.UpdateHandle updateWorkflowExecution(
      UpdateWorkflowExecutionRequest request, Deadline deadline);

  PollWorkflowExecutionUpdateResponse pollUpdateWorkflowExecution(
      PollWorkflowExecutionUpdateRequest request, Deadline deadline);

  DescribeWorkflowExecutionResponse describeWorkflowExecution();

  void completeQuery(QueryId queryId, RespondQueryTaskCompletedRequest completeRequest);

  StickyExecutionAttributes getStickyExecutionAttributes();

  Optional<TestWorkflowMutableState> getParent();

  @Nonnull
  TestWorkflowMutableState getRoot();

  boolean isTerminalState();

  RequestIdInfo getRequestIdInfo(String requestId);

  void attachRequestId(@Nonnull String requestId, EventType eventType, long eventId);

  List<Callback> getCompletionCallbacks();

  void updateRequestIdToEventId(Map<String, RequestIdInfo> requestIdToEventId);
}
