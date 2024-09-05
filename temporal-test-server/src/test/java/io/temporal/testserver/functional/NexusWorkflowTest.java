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

package io.temporal.testserver.functional;

import static org.junit.Assume.assumeFalse;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Durations;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.failure.v1.NexusOperationFailureInfo;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.NexusOperationCanceledEventAttributes;
import io.temporal.api.nexus.v1.*;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.testservice.NexusOperationRef;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class NexusWorkflowTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setDoNotStart(true)
          .setWorkflowTypes(EchoNexusHandlerWorkflowImpl.class)
          .build();

  private final Payload defaultInput =
      Payload.newBuilder().setData(ByteString.copyFromUtf8("input")).build();
  private Endpoint testEndpoint;
  private final String testService = "test-service";
  private final String testOperation = "test-operation";

  @Before
  public void setup() {
    // TODO: remove this skip once 1.25.0 is officially released and
    // https://github.com/temporalio/sdk-java/issues/2165 is resolved
    assumeFalse(
        "Nexus APIs are not supported for server versions < 1.25.0",
        testWorkflowRule.isUseExternalService());

    testEndpoint = createEndpoint("nexus-workflow-test-endpoint");
  }

  @Test
  public void testNexusOperationSyncCompletion() {
    CompletableFuture<?> nexusPoller =
        pollNexusTask()
            .thenCompose(
                task ->
                    completeNexusTask(
                        task.getTaskToken(), task.getRequest().getStartOperation().getPayload()));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationSyncCompletionWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for Nexus operation result to be recorded
      nexusPoller.get();

      pollResp = pollWorkflowTask();
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      Assert.assertEquals(1, events.size());

      HistoryEvent completedEvent = events.get(0);
      Assert.assertEquals(
          "input",
          completedEvent
              .getNexusOperationCompletedEventAttributes()
              .getResult()
              .getData()
              .toStringUtf8());

      completeWorkflow(pollResp.getTaskToken());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationAsyncCompletion() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub callerStub = newWorkflowStub("TestNexusOperationAsyncCompletionWorkflow");
      WorkflowExecution callerExecution = callerStub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse callerTask = pollWorkflowTask();
      completeWorkflowTask(callerTask.getTaskToken(), newScheduleOperationCommand());

      // Wait for scheduled task to be completed
      ByteString operationRef = nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      callerTask = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      completeWorkflowTask(callerTask.getTaskToken());

      // Manually start handler WF with callback
      TaskQueue handlerWFTaskQueue = TaskQueue.newBuilder().setName("nexus-handler-tq").build();
      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .startWorkflowExecution(
              StartWorkflowExecutionRequest.newBuilder()
                  .setRequestId(UUID.randomUUID().toString())
                  .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                  .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                  .setWorkflowType(
                      WorkflowType.newBuilder().setName("EchoNexusHandlerWorkflowImpl"))
                  .setTaskQueue(handlerWFTaskQueue)
                  .setInput(Payloads.newBuilder().addPayloads(defaultInput))
                  .setIdentity("test")
                  .addCompletionCallbacks(
                      Callback.newBuilder()
                          .setNexus(Callback.Nexus.newBuilder().setUrlBytes(operationRef)))
                  .build());

      // CaN handler workflow to verify callback is copied to new run
      PollWorkflowTaskQueueResponse handlerTask = pollWorkflowTask(handlerWFTaskQueue);
      completeWorkflowTask(
          handlerTask.getTaskToken(),
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
              .setContinueAsNewWorkflowExecutionCommandAttributes(
                  ContinueAsNewWorkflowExecutionCommandAttributes.getDefaultInstance())
              .build());

      // Complete handler workflow
      handlerTask = pollWorkflowTask(handlerWFTaskQueue);
      completeWorkflow(
          handlerTask.getTaskToken(),
          Payload.newBuilder().setData(ByteString.copyFromUtf8("operation result")).build());

      // Verify operation completion is recorded and triggers caller workflow progress
      callerTask = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      completeWorkflow(callerTask.getTaskToken());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationAsyncHandlerCanceled() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub callerStub = newWorkflowStub("TestNexusOperationAsyncHandlerCanceledWorkflow");
      WorkflowExecution callerExecution = callerStub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse callerTask = pollWorkflowTask();
      completeWorkflowTask(callerTask.getTaskToken(), newScheduleOperationCommand());

      // Wait for scheduled task to be completed
      ByteString operationRef = nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      callerTask = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      completeWorkflowTask(callerTask.getTaskToken());

      // Manually start handler WF with callback
      TaskQueue handlerWFTaskQueue = TaskQueue.newBuilder().setName("nexus-handler-tq").build();
      StartWorkflowExecutionResponse startResp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .startWorkflowExecution(
                  StartWorkflowExecutionRequest.newBuilder()
                      .setRequestId(UUID.randomUUID().toString())
                      .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                      .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                      .setWorkflowType(
                          WorkflowType.newBuilder().setName("EchoNexusHandlerWorkflowImpl"))
                      .setTaskQueue(handlerWFTaskQueue)
                      .setInput(Payloads.newBuilder().addPayloads(defaultInput))
                      .setIdentity("test")
                      .addCompletionCallbacks(
                          Callback.newBuilder()
                              .setNexus(Callback.Nexus.newBuilder().setUrlBytes(operationRef)))
                      .build());

      // Cancel handler workflow
      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .requestCancelWorkflowExecution(
              RequestCancelWorkflowExecutionRequest.newBuilder()
                  .setRequestId(UUID.randomUUID().toString())
                  .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                  .setWorkflowExecution(
                      WorkflowExecution.newBuilder()
                          .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                          .setRunId(startResp.getRunId()))
                  .setReason("test handler cancelled")
                  .setIdentity("test")
                  .build());
      PollWorkflowTaskQueueResponse handlerTask = pollWorkflowTask(handlerWFTaskQueue);
      completeWorkflowTask(
          handlerTask.getTaskToken(),
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION)
              .setCancelWorkflowExecutionCommandAttributes(
                  CancelWorkflowExecutionCommandAttributes.newBuilder()
                      .setDetails(
                          Payloads.newBuilder()
                              .addPayloads(
                                  Payload.newBuilder()
                                      .setData(
                                          ByteString.copyFromUtf8("handler workflow cancelled")))))
              .build());

      // Verify operation completion is recorded and triggers caller workflow progress
      callerTask = pollWorkflowTask();
      completeWorkflow(callerTask.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
      Assert.assertEquals(1, events.size());
      NexusOperationCanceledEventAttributes canceledEvent =
          events.get(0).getNexusOperationCanceledEventAttributes();
      Assert.assertFalse(canceledEvent.hasFailure());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationAsyncHandlerTerminated() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub callerStub = newWorkflowStub("TestNexusOperationAsyncHandlerTerminatedWorkflow");
      WorkflowExecution callerExecution = callerStub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse callerTask = pollWorkflowTask();
      completeWorkflowTask(callerTask.getTaskToken(), newScheduleOperationCommand());

      // Wait for scheduled task to be completed
      ByteString operationRef = nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      callerTask = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      completeWorkflowTask(callerTask.getTaskToken());

      // Manually start handler WF with callback
      TaskQueue handlerWFTaskQueue = TaskQueue.newBuilder().setName("nexus-handler-tq").build();
      StartWorkflowExecutionResponse startResp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .startWorkflowExecution(
                  StartWorkflowExecutionRequest.newBuilder()
                      .setRequestId(UUID.randomUUID().toString())
                      .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                      .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                      .setWorkflowType(
                          WorkflowType.newBuilder().setName("EchoNexusHandlerWorkflowImpl"))
                      .setTaskQueue(handlerWFTaskQueue)
                      .setInput(Payloads.newBuilder().addPayloads(defaultInput))
                      .setIdentity("test")
                      .addCompletionCallbacks(
                          Callback.newBuilder()
                              .setNexus(Callback.Nexus.newBuilder().setUrlBytes(operationRef)))
                      .build());

      // Terminate handler workflow
      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .terminateWorkflowExecution(
              TerminateWorkflowExecutionRequest.newBuilder()
                  .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                  .setWorkflowExecution(
                      WorkflowExecution.newBuilder()
                          .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                          .setRunId(startResp.getRunId()))
                  .setReason("test handler terminated")
                  .setDetails(
                      Payloads.newBuilder()
                          .addPayloads(
                              Payload.newBuilder()
                                  .setData(ByteString.copyFromUtf8("handler workflow terminated"))))
                  .setIdentity("test")
                  .build());

      // Verify operation failure is recorded and triggers caller workflow progress
      callerTask = pollWorkflowTask();
      completeWorkflow(callerTask.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationFailedEventAttributes().getFailure();
      assertOperationFailureInfo(operationId, failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("operation terminated", cause.getMessage());
      Assert.assertNotNull(cause.getApplicationFailureInfo());
      Assert.assertTrue(cause.getApplicationFailureInfo().getNonRetryable());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationAsyncHandlerTimeout() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub callerStub = newWorkflowStub("TestNexusOperationAsyncHandlerTimeoutWorkflow");
      WorkflowExecution callerExecution = callerStub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse callerTask = pollWorkflowTask();
      completeWorkflowTask(callerTask.getTaskToken(), newScheduleOperationCommand());

      // Wait for scheduled task to be completed
      ByteString operationRef = nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      callerTask = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      completeWorkflowTask(callerTask.getTaskToken());

      // Manually start handler WF with callback
      TaskQueue handlerWFTaskQueue = TaskQueue.newBuilder().setName("nexus-handler-tq").build();
      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .startWorkflowExecution(
              StartWorkflowExecutionRequest.newBuilder()
                  .setRequestId(UUID.randomUUID().toString())
                  .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                  .setWorkflowId("TestNexusOperationAsyncHandlerWorkflow")
                  .setWorkflowType(
                      WorkflowType.newBuilder().setName("EchoNexusHandlerWorkflowImpl"))
                  .setTaskQueue(handlerWFTaskQueue)
                  .setInput(Payloads.newBuilder().addPayloads(defaultInput))
                  .setWorkflowRunTimeout(Durations.fromSeconds(1))
                  .setIdentity("test")
                  .addCompletionCallbacks(
                      Callback.newBuilder()
                          .setNexus(Callback.Nexus.newBuilder().setUrlBytes(operationRef)))
                  .build());

      // Wait for handler to timeout and verify operation completion is recorded and triggers caller
      // workflow progress
      // Verify operation failure is recorded and triggers caller workflow progress
      callerTask = pollWorkflowTask();
      completeWorkflow(callerTask.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationFailedEventAttributes().getFailure();
      assertOperationFailureInfo(operationId, failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("operation exceeded internal timeout", cause.getMessage());
      Assert.assertNotNull(cause.getApplicationFailureInfo());
      Assert.assertTrue(cause.getApplicationFailureInfo().getNonRetryable());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationCancellation() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationCancellationWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(
          pollResp.getTaskToken(),
          newScheduleOperationCommand(
              defaultScheduleOperationAttributes()
                  .setScheduleToCloseTimeout(Durations.fromSeconds(5))));
      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);

      // Wait for operation to be started
      nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
      Assert.assertEquals(1, events.size());

      // Cancel operation
      HistoryEvent scheduledEvent = events.get(0);
      Command cancelCmd =
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION)
              .setRequestCancelNexusOperationCommandAttributes(
                  RequestCancelNexusOperationCommandAttributes.newBuilder()
                      .setScheduledEventId(scheduledEvent.getEventId()))
              .build();
      completeWorkflowTask(pollResp.getTaskToken(), cancelCmd);

      // Poll for and complete cancellation task
      pollNexusTask()
          .thenCompose(
              task ->
                  completeNexusTask(
                      task.getTaskToken(),
                      Response.newBuilder()
                          .setCancelOperation(CancelOperationResponse.getDefaultInstance())
                          .build()))
          .get();

      // Poll to verify cancellation is recorded and triggers workflow progress.
      pollResp = pollWorkflowTask();
      completeWorkflow(pollResp.getTaskToken());

      events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
      Assert.assertEquals(1, events.size());
      NexusOperationCanceledEventAttributes canceledEvent =
          events.get(0).getNexusOperationCanceledEventAttributes();
      Assert.assertFalse(canceledEvent.hasFailure());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationCancelBeforeStart() {
    WorkflowStub stub = newWorkflowStub("TestNexusOperationCancelBeforeStartWorkflow");
    WorkflowExecution execution = stub.start();

    // Get first WFT and respond with ScheduleNexusOperation command
    PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
    completeWorkflowTask(pollResp.getTaskToken(), true, newScheduleOperationCommand());

    // Poll for new WFT and respond with RequestCancelNexusOperation command
    pollResp = pollWorkflowTask();

    List<HistoryEvent> events =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    Assert.assertEquals(1, events.size());

    HistoryEvent scheduledEvent = events.get(0);
    Command cancelCmd =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION)
            .setRequestCancelNexusOperationCommandAttributes(
                RequestCancelNexusOperationCommandAttributes.newBuilder()
                    .setScheduledEventId(scheduledEvent.getEventId()))
            .build();
    completeWorkflowTask(pollResp.getTaskToken(), cancelCmd);

    // Poll and verify cancel triggers workflow progress
    pollResp = pollWorkflowTask();
    completeWorkflow(pollResp.getTaskToken());

    events =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
    Assert.assertEquals(1, events.size());
    NexusOperationCanceledEventAttributes canceledEvent =
        events.get(0).getNexusOperationCanceledEventAttributes();
    Assert.assertFalse(canceledEvent.hasFailure());
  }

  @Test(timeout = 15000)
  public void testNexusOperationTimeout_BeforeStart() {
    WorkflowStub stub = newWorkflowStub("TestNexusOperationTimeoutBeforeStartWorkflow");
    WorkflowExecution execution = stub.start();

    // Get first WFT and respond with ScheduleNexusOperation command
    PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
    completeWorkflowTask(
        pollResp.getTaskToken(),
        newScheduleOperationCommand(
            defaultScheduleOperationAttributes()
                .setScheduleToCloseTimeout(Durations.fromSeconds(12))));
    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);

    try {
      // Poll for Nexus task but do not complete it
      PollNexusTaskQueueResponse nexusPollResp = pollNexusTask().get();
      Assert.assertTrue(nexusPollResp.getRequest().hasStartOperation());

      // Request timeout and long poll deadline are both 10s, so sleep to give some buffer so poll
      // request doesn't timeout.
      Thread.sleep(2000);

      // Poll again to verify task is resent on timeout
      nexusPollResp = pollNexusTask().get();
      NexusOperationRef ref = NexusOperationRef.fromBytes(nexusPollResp.getTaskToken());
      Assert.assertEquals(2, ref.getAttempt());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    }

    // Poll to wait for new task after operation times out
    pollResp = pollWorkflowTask();
    completeWorkflow(pollResp.getTaskToken());

    List<HistoryEvent> events =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT);
    Assert.assertEquals(1, events.size());
    io.temporal.api.failure.v1.Failure failure =
        events.get(0).getNexusOperationTimedOutEventAttributes().getFailure();
    assertOperationFailureInfo(failure.getNexusOperationExecutionFailureInfo());
    Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
    io.temporal.api.failure.v1.Failure cause = failure.getCause();
    Assert.assertEquals("operation timed out", cause.getMessage());
    Assert.assertNotNull(cause.getTimeoutFailureInfo());
    Assert.assertEquals(
        TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE, cause.getTimeoutFailureInfo().getTimeoutType());
  }

  @Test
  public void testNexusOperationTimeout_AfterStart() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationTimeoutAfterStartWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(
          pollResp.getTaskToken(),
          newScheduleOperationCommand(
              defaultScheduleOperationAttributes()
                  .setScheduleToCloseTimeout(Durations.fromSeconds(2))));
      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);

      // Wait for operation to be started
      nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      completeWorkflowTask(pollResp.getTaskToken());

      // Poll to wait for new task after operation times out
      pollResp = pollWorkflowTask();
      completeWorkflow(pollResp.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationTimedOutEventAttributes().getFailure();
      assertOperationFailureInfo(operationId, failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("operation timed out", cause.getMessage());
      Assert.assertNotNull(cause.getTimeoutFailureInfo());
      Assert.assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
          cause.getTimeoutFailureInfo().getTimeoutType());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test(timeout = 30000)
  public void testNexusOperationTimeout_AfterCancel() {
    String operationId = UUID.randomUUID().toString();
    CompletableFuture<ByteString> nexusPoller =
        pollNexusTask().thenCompose(task -> completeNexusTask(task.getTaskToken(), operationId));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationTimeoutAfterCancelWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(
          pollResp.getTaskToken(),
          newScheduleOperationCommand(
              defaultScheduleOperationAttributes()
                  .setScheduleToCloseTimeout(Durations.fromSeconds(22))));
      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);

      // Wait for operation to be started
      nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
      Assert.assertEquals(1, events.size());

      // Cancel operation
      HistoryEvent scheduledEvent = events.get(0);
      Command cancelCmd =
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION)
              .setRequestCancelNexusOperationCommandAttributes(
                  RequestCancelNexusOperationCommandAttributes.newBuilder()
                      .setScheduledEventId(scheduledEvent.getEventId()))
              .build();
      completeWorkflowTask(pollResp.getTaskToken(), cancelCmd);

      // Poll for cancellation task but do not complete it
      PollNexusTaskQueueResponse nexusPollResp = pollNexusTask().get();
      Assert.assertTrue(nexusPollResp.getRequest().hasCancelOperation());

      // Request timeout and long poll deadline are both 10s, so sleep to give some buffer so poll
      // request doesn't timeout.
      Thread.sleep(2000);

      // Poll for cancellation task again to confirm it is retried on timeout
      nexusPollResp = pollNexusTask().get();
      Assert.assertTrue(nexusPollResp.getRequest().hasCancelOperation());
      NexusOperationRef ref = NexusOperationRef.fromBytes(nexusPollResp.getTaskToken());
      Assert.assertTrue(ref.getAttempt() > 1);

      // Request timeout and long poll deadline are both 10s, so sleep to give some buffer so poll
      // request doesn't timeout.
      Thread.sleep(2000);

      // Poll to wait for new task after operation times out
      pollResp = pollWorkflowTask();
      completeWorkflow(pollResp.getTaskToken());

      events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationTimedOutEventAttributes().getFailure();
      assertOperationFailureInfo(operationId, failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("operation timed out", cause.getMessage());
      Assert.assertNotNull(cause.getTimeoutFailureInfo());
      Assert.assertEquals(
          TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE,
          cause.getTimeoutFailureInfo().getTimeoutType());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationError() {
    Response unsuccessfulResp =
        Response.newBuilder()
            .setStartOperation(
                StartOperationResponse.newBuilder()
                    .setOperationError(
                        UnsuccessfulOperationError.newBuilder()
                            .setOperationState("failed")
                            .setFailure(
                                Failure.newBuilder().setMessage("deliberate test failure"))))
            .build();
    CompletableFuture<?> nexusPoller =
        pollNexusTask()
            .thenCompose(task -> completeNexusTask(task.getTaskToken(), unsuccessfulResp));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationErrorWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for Nexus operation result to be recorded
      nexusPoller.get();

      // Poll and verify operation failure is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      completeWorkflow(pollResp.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationFailedEventAttributes().getFailure();
      assertOperationFailureInfo(failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("deliberate test failure", cause.getMessage());
      Assert.assertNotNull(cause.getApplicationFailureInfo());
      Assert.assertEquals("NexusOperationFailure", cause.getApplicationFailureInfo().getType());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationHandlerError() {
    // Polls for nexus task -> respond with retryable failure -> poll for nexus task -> respond with
    // non-retryable failure
    CompletableFuture<?> nexusPoller =
        pollNexusTask()
            .thenCompose(
                task ->
                    failNexusTask(
                        task.getTaskToken(),
                        HandlerError.newBuilder()
                            .setErrorType("INTERNAL")
                            .setFailure(
                                Failure.newBuilder().setMessage("deliberate retryable error"))
                            .build()))
            .thenRunAsync(
                () ->
                    pollNexusTask()
                        .thenCompose(
                            task ->
                                failNexusTask(
                                    task.getTaskToken(),
                                    HandlerError.newBuilder()
                                        .setErrorType("INVALID_ARGUMENT")
                                        .setFailure(
                                            Failure.newBuilder()
                                                .setMessage("deliberate terminal error"))
                                        .build())));

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationHandlerErrorWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for Nexus operation error to be recorded
      nexusPoller.get();

      // Poll and verify operation failure is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      completeWorkflow(pollResp.getTaskToken());

      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED);
      Assert.assertEquals(1, events.size());
      io.temporal.api.failure.v1.Failure failure =
          events.get(0).getNexusOperationFailedEventAttributes().getFailure();
      assertOperationFailureInfo(failure.getNexusOperationExecutionFailureInfo());
      Assert.assertEquals("nexus operation completed unsuccessfully", failure.getMessage());
      io.temporal.api.failure.v1.Failure cause = failure.getCause();
      Assert.assertEquals("deliberate terminal error", cause.getMessage());
      Assert.assertNotNull(cause.getApplicationFailureInfo());
      Assert.assertEquals("INVALID_ARGUMENT", cause.getApplicationFailureInfo().getType());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  @Test
  public void testNexusOperationInvalidRef() {
    // Polls for nexus task -> respond with invalid task token ->  respond with correct task token
    CompletableFuture<?> nexusPoller =
        pollNexusTask()
            .thenCompose(
                task -> {
                  NexusOperationRef valid = NexusOperationRef.fromBytes(task.getTaskToken());
                  NexusOperationRef invalid =
                      new NexusOperationRef(
                          valid.getExecutionId(),
                          valid.getScheduledEventId(),
                          (int) (valid.getAttempt() + 20),
                          valid.isCancel());

                  try {
                    completeNexusTask(
                            invalid.toBytes(), task.getRequest().getStartOperation().getPayload())
                        .get();
                  } catch (Exception e) {
                    Assert.fail(e.getMessage());
                  }

                  return completeNexusTask(
                      task.getTaskToken(), task.getRequest().getStartOperation().getPayload());
                });

    try {
      WorkflowStub stub = newWorkflowStub("TestNexusOperationSyncCompletionWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for Nexus operation result to be recorded
      nexusPoller.get();

      pollResp = pollWorkflowTask();
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      Assert.assertEquals(1, events.size());

      HistoryEvent completedEvent = events.get(0);
      Assert.assertEquals(
          "input",
          completedEvent
              .getNexusOperationCompletedEventAttributes()
              .getResult()
              .getData()
              .toStringUtf8());

      completeWorkflow(pollResp.getTaskToken());
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  private WorkflowStub newWorkflowStub(String name) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    return testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(name, options);
  }

  private PollWorkflowTaskQueueResponse pollWorkflowTask() {
    return pollWorkflowTask(
        TaskQueue.newBuilder()
            .setName(testWorkflowRule.getTaskQueue())
            .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
            .build());
  }

  private PollWorkflowTaskQueueResponse pollWorkflowTask(TaskQueue taskQueue) {
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .pollWorkflowTaskQueue(
            PollWorkflowTaskQueueRequest.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .setTaskQueue(taskQueue)
                .setIdentity("test")
                .build());
  }

  private void completeWorkflowTask(ByteString taskToken, Command... commands) {
    completeWorkflowTask(taskToken, false, commands);
  }

  private void completeWorkflowTask(
      ByteString taskToken, boolean forceNewTask, Command... commands) {
    testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .respondWorkflowTaskCompleted(
            RespondWorkflowTaskCompletedRequest.newBuilder()
                .setIdentity("test")
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .setTaskToken(taskToken)
                .setForceCreateNewWorkflowTask(forceNewTask)
                .addAllCommands(Arrays.asList(commands))
                .build());
  }

  private ScheduleNexusOperationCommandAttributes.Builder defaultScheduleOperationAttributes() {
    return ScheduleNexusOperationCommandAttributes.newBuilder()
        .setEndpoint(testEndpoint.getSpec().getName())
        .setService(testService)
        .setOperation(testOperation)
        .setInput(defaultInput);
  }

  private Command newScheduleOperationCommand() {
    return newScheduleOperationCommand(defaultScheduleOperationAttributes());
  }

  private Command newScheduleOperationCommand(
      ScheduleNexusOperationCommandAttributes.Builder attr) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION)
        .setScheduleNexusOperationCommandAttributes(attr)
        .build();
  }

  private void completeWorkflow(ByteString taskToken) {
    completeWorkflow(taskToken, Payload.getDefaultInstance());
  }

  private void completeWorkflow(ByteString taskToken, Payload result) {
    Command cmd =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .setCompleteWorkflowExecutionCommandAttributes(
                CompleteWorkflowExecutionCommandAttributes.newBuilder()
                    .setResult(Payloads.newBuilder().addPayloads(result)))
            .build();

    completeWorkflowTask(taskToken, cmd);
  }

  private CompletableFuture<PollNexusTaskQueueResponse> pollNexusTask() {
    return CompletableFuture.supplyAsync(
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .pollNexusTaskQueue(
                    PollNexusTaskQueueRequest.newBuilder()
                        .setIdentity(UUID.randomUUID().toString())
                        .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                        .setTaskQueue(
                            TaskQueue.newBuilder()
                                .setName(testWorkflowRule.getTaskQueue())
                                .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL))
                        .build()));
  }

  private CompletableFuture<ByteString> completeNexusTask(ByteString taskToken, Payload result) {
    return completeNexusTask(
        taskToken,
        Response.newBuilder()
            .setStartOperation(
                StartOperationResponse.newBuilder()
                    .setSyncSuccess(StartOperationResponse.Sync.newBuilder().setPayload(result)))
            .build());
  }

  private CompletableFuture<ByteString> completeNexusTask(
      ByteString taskToken, String operationId) {
    return completeNexusTask(
        taskToken,
        Response.newBuilder()
            .setStartOperation(
                StartOperationResponse.newBuilder()
                    .setAsyncSuccess(
                        StartOperationResponse.Async.newBuilder().setOperationId(operationId)))
            .build());
  }

  private CompletableFuture<ByteString> completeNexusTask(ByteString taskToken, Response resp) {
    return CompletableFuture.supplyAsync(
        () -> {
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .respondNexusTaskCompleted(
                  RespondNexusTaskCompletedRequest.newBuilder()
                      .setIdentity(UUID.randomUUID().toString())
                      .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                      .setTaskToken(taskToken)
                      .setResponse(resp)
                      .build());
          return taskToken;
        });
  }

  private CompletableFuture<RespondNexusTaskFailedResponse> failNexusTask(
      ByteString taskToken, HandlerError err) {
    return CompletableFuture.supplyAsync(
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .respondNexusTaskFailed(
                    RespondNexusTaskFailedRequest.newBuilder()
                        .setIdentity(UUID.randomUUID().toString())
                        .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                        .setTaskToken(taskToken)
                        .setError(err)
                        .build()));
  }

  private void assertOperationFailureInfo(NexusOperationFailureInfo info) {
    assertOperationFailureInfo("", info);
  }

  private void assertOperationFailureInfo(String operationID, NexusOperationFailureInfo info) {
    Assert.assertNotNull(info);
    Assert.assertEquals(operationID, info.getOperationId());
    Assert.assertEquals(testEndpoint.getSpec().getName(), info.getEndpoint());
    Assert.assertEquals(testService, info.getService());
    Assert.assertEquals(testOperation, info.getOperation());
  }

  private Endpoint createEndpoint(String name) {
    return testWorkflowRule
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .createNexusEndpoint(
            CreateNexusEndpointRequest.newBuilder()
                .setSpec(
                    EndpointSpec.newBuilder()
                        .setName(name)
                        .setDescription(
                            Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
                        .setTarget(
                            EndpointTarget.newBuilder()
                                .setWorker(
                                    EndpointTarget.Worker.newBuilder()
                                        .setNamespace(
                                            testWorkflowRule.getTestEnvironment().getNamespace())
                                        .setTaskQueue(testWorkflowRule.getTaskQueue()))))
                .build())
        .getEndpoint();
  }

  public static class EchoNexusHandlerWorkflowImpl
      implements TestWorkflows.PrimitiveNexusHandlerWorkflow {
    @Override
    public Object execute(String input) {
      return input;
    }
  }
}
