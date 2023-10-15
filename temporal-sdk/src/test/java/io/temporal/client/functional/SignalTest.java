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

package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class SignalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(QuickWorkflowWithSignalImpl.class).build();

  @Test
  public void signalNonExistentWorkflow() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestSignaledWorkflow.class, "non-existing-id");
    assertThrows(WorkflowNotFoundException.class, () -> workflow.signal("some-value"));
  }

  @Test
  public void signalCompletedWorkflow() {
    TestWorkflows.TestSignaledWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestSignaledWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    workflow.execute();
    assertThrows(WorkflowNotFoundException.class, () -> workflow.signal("some-value"));
  }

  @Test(timeout = 50000)
  public void signalWithStartWithDelay() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setStartDelay(Duration.ofSeconds(5))
            .build();
    WorkflowStub stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub("TestSignaledWorkflow", workflowOptions);

    WorkflowExecution workflowExecution =
        stubF.signalWithStart("testSignal", new Object[] {"testArg"}, new Object[] {});

    assertEquals("done", stubF.getResult(String.class));
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(workflowExecution.getWorkflowId());
    List<WorkflowExecutionStartedEventAttributes> workflowExecutionStartedEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
            .map(x -> x.getWorkflowExecutionStartedEventAttributes())
            .collect(Collectors.toList());
    assertEquals(1, workflowExecutionStartedEvents.size());
    assertEquals(
        Duration.ofSeconds(5),
        ProtobufTimeUtils.toJavaDuration(
            workflowExecutionStartedEvents.get(0).getFirstWorkflowTaskBackoff()));
  }

  public static class QuickWorkflowWithSignalImpl implements TestWorkflows.TestSignaledWorkflow {

    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void signal(String arg) {}
  }
}
