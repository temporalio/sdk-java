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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.TimerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.*;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowMetadataTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflow.class, TestChild.class)
          .build();

  static final String summary = "my-wf-summary";
  static final String details = "my-wf-details";
  static final String childSummary = "child-summary";
  static final String childDetails = "child-details";
  static final String childTimerSummary = "child-timer-summary";

  @Before
  public void checkRealServer() {
    assumeTrue("skipping for test server", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void testChildWorkflowWithMetaData() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setStaticSummary(summary)
            .setStaticDetails(details)
            .build();
    TestWorkflow1 stub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflow1.class, options);

    String childWorkflowId = stub.execute(testWorkflowRule.getTaskQueue());

    WorkflowExecution exec = WorkflowStub.fromTyped(stub).getExecution();
    assertWorkflowMetadata(exec.getWorkflowId(), summary, details);
    assertWorkflowMetadata(childWorkflowId, childSummary, childDetails);

    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(childWorkflowId);
    List<HistoryEvent> timerStartedEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasTimerStartedEventAttributes)
            .collect(Collectors.toList());
    assertEventMetadata(timerStartedEvents.get(0), childTimerSummary, null);
  }

  private void assertWorkflowMetadata(String workflowId, String summary, String details) {
    DescribeWorkflowExecutionResponse describe =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                    .build());
    String describedSummary =
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            describe.getExecutionConfig().getUserMetadata().getSummary(),
            String.class,
            String.class);
    String describedDetails =
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            describe.getExecutionConfig().getUserMetadata().getDetails(),
            String.class,
            String.class);
    assertEquals(summary, describedSummary);
    assertEquals(details, describedDetails);
  }

  private void assertEventMetadata(HistoryEvent event, String summary, String details) {
    if (summary != null) {
      String describedSummary =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getSummary(), String.class, String.class);
      assertEquals(summary, describedSummary);
    }
    if (details != null) {
      String describedDetails =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getDetails(), String.class, String.class);
      assertEquals(details, describedDetails);
    }
  }

  public static class TestParentWorkflow implements TestWorkflow1 {

    private final ITestChild child1 =
        Workflow.newChildWorkflowStub(
            ITestChild.class,
            ChildWorkflowOptions.newBuilder()
                .setStaticDetails(childDetails)
                .setStaticSummary(childSummary)
                .build());

    @Override
    public String execute(String taskQueue) {
      child1.execute("World!", 1);
      return Workflow.getWorkflowExecution(child1).get().getWorkflowId();
    }
  }

  public static class TestChild implements ITestChild {

    @Override
    public String execute(String arg, int delay) {
      Workflow.newTimer(
              Duration.ofMillis(delay),
              TimerOptions.newBuilder().setSummary(childTimerSummary).build())
          .get();
      return arg.toUpperCase();
    }
  }
}
