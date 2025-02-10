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

import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Rule;
import org.junit.Test;

import static java.util.UUID.randomUUID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SignalLinksTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void testSignalWithLinks() {
    WorkflowStub stub = testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflow");
    WorkflowExecution execution = stub.start();

    Link testLink = createTestLink(execution.getRunId());
    SignalWorkflowExecutionRequest signalRequest =
        SignalWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowExecution(execution)
            .setSignalName("test-signal")
            .setInput(Payloads.newBuilder().build())
            .addLinks(testLink)
            .build();

    testWorkflowRule
        .getWorkflowServiceStubs()
        .blockingStub()
        .signalWorkflowExecution(signalRequest);

    stub.getResult(Void.class);

    verifySignalLink(execution, testLink);
  }

  @Test
  public void testSignalWithStartLinks() {
    String workflowId = "test-workflow-id";
    Link testLink = createTestLink("some-run-id");

    SignalWithStartWorkflowExecutionRequest signalWithStartRequest =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setTaskQueue(TaskQueue.newBuilder().setName(testWorkflowRule.getTaskQueue()).build())
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowType(WorkflowType.newBuilder().setName("TestWorkflow").build())
            .setSignalInput(Payloads.newBuilder().build())
            .setRequestId(randomUUID().toString())
            .setSignalName("test-signal")
            .setWorkflowId(workflowId)
            .addLinks(testLink)
            .build();

    SignalWithStartWorkflowExecutionResponse response =
        testWorkflowRule
            .getWorkflowServiceStubs()
            .blockingStub()
            .signalWithStartWorkflowExecution(signalWithStartRequest);

    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(workflowId)
            .setRunId(response.getRunId())
            .build();

    verifySignalLink(execution, testLink);
    verifyStartEventLink(execution, testLink);
  }

  private Link createTestLink(String runId) {
    return Link.newBuilder()
        .setWorkflowEvent(
            Link.WorkflowEvent.newBuilder()
                .setWorkflowId("someWorkflow")
                .setNamespace("default")
                .setRunId(runId)
                .build())
        .build();
  }

  private void verifySignalLink(WorkflowExecution execution, Link expectedLink) {
    GetWorkflowExecutionHistoryResponse history = getHistory(execution);
    boolean foundSignalWithLink = false;

    for (HistoryEvent event : history.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) {
        WorkflowExecutionSignaledEventAttributes attrs =
            event.getWorkflowExecutionSignaledEventAttributes();
        if ("test-signal".equals(attrs.getSignalName())) {
          assertEquals(1, event.getLinksCount());
          assertEquals(expectedLink, event.getLinks(0));
          foundSignalWithLink = true;
          break;
        }
      }
    }

    assertTrue("Should have found signal event with link", foundSignalWithLink);
  }

  private void verifyStartEventLink(WorkflowExecution execution, Link expectedLink) {
    GetWorkflowExecutionHistoryResponse history = getHistory(execution);
    boolean foundStartWithLink = false;

    for (HistoryEvent event : history.getHistory().getEventsList()) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
        assertEquals("Link should be present on start event", 1, event.getLinksCount());
        assertEquals("Link in start event should match", expectedLink, event.getLinks(0));
        foundStartWithLink = true;
        break;
      }
    }

    assertTrue("Should have found start event with link", foundStartWithLink);
  }

  private GetWorkflowExecutionHistoryResponse getHistory(WorkflowExecution execution) {
    return testWorkflowRule
        .getWorkflowServiceStubs()
        .blockingStub()
        .getWorkflowExecutionHistory(
            GetWorkflowExecutionHistoryRequest.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setExecution(execution)
                .build());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod(name = "TestWorkflow")
    void run();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void run() {
      // Empty workflow that completes quickly
    }
  }
}
