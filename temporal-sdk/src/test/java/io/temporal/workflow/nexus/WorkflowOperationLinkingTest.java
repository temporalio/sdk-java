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

package io.temporal.workflow.nexus;

import static io.temporal.internal.common.WorkflowExecutionUtils.getEventOfType;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.WorkflowClientOperationHandlers;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowOperationLinkingTest extends BaseNexusTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, TestOperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setUseExternalService(true)
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void testWorkflowOperationLinks() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("Hello from operation workflow " + testWorkflowRule.getTaskQueue(), result);
    String originalWorkflowId = WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId();
    History history =
        testWorkflowRule.getWorkflowClient().fetchHistory(originalWorkflowId).getHistory();
    // Assert that the operation started event has a link to the workflow execution started event
    HistoryEvent nexusStartedEvent =
        getEventOfType(history, EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED);
    Assert.assertEquals(1, nexusStartedEvent.getLinksCount());
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
        nexusStartedEvent.getLinks(0).getWorkflowEvent().getEventRef().getEventType());
    // Assert that the started workflow has a link to the original workflow
    History linkedHistory =
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(nexusStartedEvent.getLinks(0).getWorkflowEvent().getWorkflowId())
            .getHistory();
    HistoryEvent linkedStartedEvent = linkedHistory.getEventsList().get(0);
    Assert.assertEquals(1, linkedStartedEvent.getLinksCount());
    Assert.assertEquals(
        originalWorkflowId, linkedStartedEvent.getLinks(0).getWorkflowEvent().getWorkflowId());
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      // Start an asynchronous operation backed by a workflow
      NexusOperationHandle<String> asyncOpHandle =
          Workflow.startNexusOperation(serviceStub::operation, input);
      NexusOperationExecution asyncExec = asyncOpHandle.getExecution().get();
      // Signal the operation to unblock, this makes sure the operation doesn't complete before the
      // operation
      // started event is written to history
      Workflow.newExternalWorkflowStub(OperationWorkflow.class, asyncExec.getOperationId().get())
          .unblock();
      return asyncOpHandle.getResult().get();
    }
  }

  @WorkflowInterface
  public interface OperationWorkflow {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void unblock();
  }

  public static class TestOperationWorkflow implements OperationWorkflow {
    boolean unblocked = false;

    @Override
    public String execute(String arg) {
      Workflow.await(() -> unblocked);
      return "Hello from operation workflow " + arg;
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowClientOperationHandlers.fromWorkflowMethod(
          (context, details, client, input) ->
              client.newWorkflowStub(
                      AsyncWorkflowOperationTest.OperationWorkflow.class,
                      WorkflowOptions.newBuilder().setWorkflowId(details.getRequestId()).build())
                  ::execute);
    }
  }
}
