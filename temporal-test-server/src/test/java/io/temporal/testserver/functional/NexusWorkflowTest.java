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

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusWorkflowTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testNexusOperationSyncCompletion() {
    Endpoint testEndpoint = createEndpoint("test-sync-completion-endpoint");
    CompletableFuture<Void> nexusPoller = CompletableFuture.runAsync(pollAndCompleteNexusTask());

    try {
      WorkflowOptions options =
          WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
      WorkflowStub stub =
          testWorkflowRule
              .getWorkflowClient()
              .newUntypedWorkflowStub("TestNexusOperationSyncCompletionWorkflow", options);
      WorkflowExecution execution = stub.start();

      // Get first WFT and respond with ScheduleNexusOperation command
      PollWorkflowTaskQueueResponse pollResp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .pollWorkflowTaskQueue(
                  PollWorkflowTaskQueueRequest.newBuilder()
                      .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                      .setTaskQueue(
                          TaskQueue.newBuilder()
                              .setName(testWorkflowRule.getTaskQueue())
                              .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL))
                      .setIdentity("test")
                      .build());
      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .respondWorkflowTaskCompleted(
              RespondWorkflowTaskCompletedRequest.newBuilder()
                  .setIdentity("test")
                  .setTaskToken(pollResp.getTaskToken())
                  .addCommands(
                      Command.newBuilder()
                          .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION)
                          .setScheduleNexusOperationCommandAttributes(
                              ScheduleNexusOperationCommandAttributes.newBuilder()
                                  .setEndpoint(testEndpoint.getSpec().getName())
                                  .setService("service")
                                  .setOperation("operation")
                                  .setInput(
                                      Payload.newBuilder()
                                          .setData(ByteString.copyFromUtf8("input"))))
                          .build())
                  .build());

      // Wait for Nexus operation result to be recorded
      nexusPoller.get(2, TimeUnit.SECONDS);

      pollResp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .pollWorkflowTaskQueue(
                  PollWorkflowTaskQueueRequest.newBuilder()
                      .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                      .setTaskQueue(
                          TaskQueue.newBuilder()
                              .setName(testWorkflowRule.getTaskQueue())
                              .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL))
                      .setIdentity("test")
                      .build());
      Assert.assertTrue(
          pollResp.getHistory().getEventsList().stream()
              .anyMatch(
                  event -> event.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED));
      List<HistoryEvent> events =
          testWorkflowRule.getHistoryEvents(
              execution.getWorkflowId(), EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
      Assert.assertEquals(1, events.size());
      HistoryEvent completedEvent = events.get(0);

      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .respondWorkflowTaskCompleted(
              RespondWorkflowTaskCompletedRequest.newBuilder()
                  .setIdentity("test")
                  .setTaskToken(pollResp.getTaskToken())
                  .addCommands(
                      Command.newBuilder()
                          .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
                          .setCompleteWorkflowExecutionCommandAttributes(
                              CompleteWorkflowExecutionCommandAttributes.newBuilder()
                                  .setResult(
                                      Payloads.newBuilder()
                                          .addPayloads(
                                              completedEvent
                                                  .getNexusOperationCompletedEventAttributes()
                                                  .getResult()))))
                  .build());

      String result = stub.getResult(String.class);
      Assert.assertEquals(result, "input");
    } catch (Exception e) {
      System.out.println(e.getMessage());
    } finally {
      nexusPoller.cancel(true);
    }
  }

  private Runnable pollAndCompleteNexusTask() {
    return () -> {
      PollNexusTaskQueueResponse pollResp =
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
                      .build());

      testWorkflowRule
          .getWorkflowClient()
          .getWorkflowServiceStubs()
          .blockingStub()
          .respondNexusTaskCompleted(
              RespondNexusTaskCompletedRequest.newBuilder()
                  .setIdentity(UUID.randomUUID().toString())
                  .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                  .setTaskToken(pollResp.getTaskToken())
                  .setResponse(
                      Response.newBuilder()
                          .setStartOperation(
                              StartOperationResponse.newBuilder()
                                  .setSyncSuccess(
                                      StartOperationResponse.Sync.newBuilder()
                                          .setPayload(
                                              pollResp
                                                  .getRequest()
                                                  .getStartOperation()
                                                  .getPayload()))))
                  .build());
    };
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
}
