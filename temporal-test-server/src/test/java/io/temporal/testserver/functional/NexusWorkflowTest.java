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
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RequestCancelNexusOperationCommandAttributes;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.nexus.v1.*;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
      nexusPoller.get(1, TimeUnit.SECONDS);

      pollResp = pollWorkflowTask();
      Assert.assertTrue(
          pollResp.getHistory().getEventsList().stream()
              .anyMatch(
                  event -> event.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED));
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      Assert.assertEquals(1, events.size());

      HistoryEvent completedEvent = events.get(0);
      completeWorkflow(
          pollResp.getTaskToken(),
          completedEvent.getNexusOperationCompletedEventAttributes().getResult());

      String result = stub.getResult(String.class);
      Assert.assertEquals(result, "input");
    } catch (Exception e) {
      System.out.println(e.getMessage());
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

    events =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED);
    Assert.assertEquals(1, events.size());
  }

  @Test
  public void testNexusOperationTimeout() {
    WorkflowStub stub = newWorkflowStub("TestNexusOperationTimeoutWorkflow");
    WorkflowExecution execution = stub.start();

    // Get first WFT and respond with ScheduleNexusOperation command
    PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
    Command cmd =
        newScheduleOperationCommand(
            defaultScheduleOperationAttributes()
                .setScheduleToCloseTimeout(Durations.fromSeconds(1)));
    completeWorkflowTask(pollResp.getTaskToken(), cmd);

    List<HistoryEvent> events =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED);
    Assert.assertEquals(1, events.size());

    // Poll to wait for new task after operation times out
    pollWorkflowTask();

    testWorkflowRule.assertHistoryEvent(
        execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT);
  }

  @Test
  public void testRespondNexusTaskFailed() {
    CompletableFuture<?> nexusPoller =
        pollNexusTask()
            .thenCompose(
                task ->
                    failNexusTask(
                        task.getTaskToken(),
                        HandlerError.newBuilder()
                            .setErrorType("BAD_REQUEST")
                            .setFailure(Failure.newBuilder().setMessage("deliberate error"))
                            .build()));

    try {
      WorkflowStub stub = newWorkflowStub("TestRespondNexusTaskFailedWorkflow");
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for Nexus operation error to be recorded
      nexusPoller.get(1, TimeUnit.SECONDS);

      testWorkflowRule.assertHistoryEvent(
          execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED);
    } catch (Exception e) {
      System.out.println(e.getMessage());
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
      PollWorkflowTaskQueueResponse pollResp = pollWorkflowTask();
      completeWorkflowTask(pollResp.getTaskToken(), newScheduleOperationCommand());

      // Wait for scheduled task to be completed
      ByteString operationRef = nexusPoller.get();

      // Poll and verify started event is recorded and triggers workflow progress
      pollResp = pollWorkflowTask();
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
      Assert.assertEquals(1, events.size());
      completeWorkflowTask(pollResp.getTaskToken());

      // Manually start handler WF with callback
      TaskQueue handlerWFTaskQueue =
          TaskQueue.newBuilder()
              .setName("nexus-handler-tq")
              .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
              .build();
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
                          WorkflowType.newBuilder()
                              .setName(EchoNexusHandlerWorkflowImpl.class.getName()))
                      .setTaskQueue(handlerWFTaskQueue)
                      .setInput(Payloads.newBuilder().addPayloads(defaultInput))
                      .setIdentity("test")
                      .addCompletionCallbacks(
                          Callback.newBuilder()
                              .setNexus(Callback.Nexus.newBuilder().setUrlBytes(operationRef)))
                      .build());

      // Complete handler workflow
      pollResp = pollWorkflowTask(handlerWFTaskQueue);
      completeWorkflow(pollResp.getTaskToken(), defaultInput);

      // Verify operation completion is recorded and triggers caller workflow progress
      pollResp = pollWorkflowTask();
      events =
          testWorkflowRule.getHistoryEvents(
              callerExecution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      Assert.assertEquals(1, events.size());
      completeWorkflow(pollResp.getTaskToken(), Payload.getDefaultInstance());
    } catch (Exception e) {
      System.out.println(e.getMessage());
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
                .setTaskToken(taskToken)
                .setForceCreateNewWorkflowTask(forceNewTask)
                .addAllCommands(Arrays.asList(commands))
                .build());
  }

  private ScheduleNexusOperationCommandAttributes.Builder defaultScheduleOperationAttributes() {
    return ScheduleNexusOperationCommandAttributes.newBuilder()
        .setEndpoint(testEndpoint.getSpec().getName())
        .setService("test-service")
        .setOperation("test-operation")
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

  private CompletableFuture<RespondNexusTaskCompletedResponse> completeNexusTask(
      ByteString taskToken, Payload result) {
    return CompletableFuture.supplyAsync(
        () ->
            testWorkflowRule
                .getWorkflowClient()
                .getWorkflowServiceStubs()
                .blockingStub()
                .respondNexusTaskCompleted(
                    RespondNexusTaskCompletedRequest.newBuilder()
                        .setIdentity(UUID.randomUUID().toString())
                        .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                        .setTaskToken(taskToken)
                        .setResponse(
                            Response.newBuilder()
                                .setStartOperation(
                                    StartOperationResponse.newBuilder()
                                        .setSyncSuccess(
                                            StartOperationResponse.Sync.newBuilder()
                                                .setPayload(result))))
                        .build()));
  }

  private CompletableFuture<ByteString> completeNexusTask(
      ByteString taskToken, String operationId) {
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
                      .setResponse(
                          Response.newBuilder()
                              .setStartOperation(
                                  StartOperationResponse.newBuilder()
                                      .setAsyncSuccess(
                                          StartOperationResponse.Async.newBuilder()
                                              .setOperationId(operationId))))
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
