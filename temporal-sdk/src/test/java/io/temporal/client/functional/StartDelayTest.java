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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.*;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class StartDelayTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNoArgsWorkflowsFuncImpl.class)
          .setUseTimeskipping(false)
          .build();

  @Test
  public void startWithDelay() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setStartDelay(Duration.ofSeconds(1))
            .build();
    TestNoArgsWorkflowFunc stubF =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestNoArgsWorkflowFunc.class, workflowOptions);
    long start = System.currentTimeMillis();
    stubF.func();
    long end = System.currentTimeMillis();
    // Assert that the workflow took at least 5 seconds to start
    assertEquals(1000, end - start, 500);
    WorkflowExecution workflowExecution = WorkflowStub.fromTyped(stubF).getExecution();
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(workflowExecution.getWorkflowId());
    List<WorkflowExecutionStartedEventAttributes> workflowExecutionStartedEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasWorkflowExecutionStartedEventAttributes)
            .map(x -> x.getWorkflowExecutionStartedEventAttributes())
            .collect(Collectors.toList());
    assertEquals(1, workflowExecutionStartedEvents.size());
    assertEquals(
        Duration.ofSeconds(1),
        ProtobufTimeUtils.toJavaDuration(
            workflowExecutionStartedEvents.get(0).getFirstWorkflowTaskBackoff()));
  }

  public static class TestNoArgsWorkflowsFuncImpl implements TestNoArgsWorkflowFunc {

    @Override
    public String func() {

      return "done";
    }

    @Override
    public String update() {
      throw new UnsupportedOperationException();
    }
  }
}
