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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * If Local Activity throws an {@link Error}, it should immediately fail Workflow Task. Java Error
 * signals a problem with the Worker and shouldn't lead to a failure of a Local Activity execution
 * or a Workflow.
 */
public class LocalActivityThrowingErrorTest {

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LocalActivityThrowsErrorWorkflow.class)
          .setActivityImplementations(new ApplicationFailureActivity())
          .build();

  @Test
  public void throwsError() {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);
    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    workflowStub.start();
    WorkflowExecution execution = workflowStub.getExecution();
    testWorkflowRule.waitForTheEndOfWFT(execution);
    List<HistoryEvent> historyEvents =
        testWorkflowRule.getHistoryEvents(execution, EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    assertTrue(historyEvents.size() > 0);
  }

  public static class LocalActivityThrowsErrorWorkflow implements NoArgsWorkflow {

    private final TestActivities.NoArgsActivity activity1 =
        Workflow.newLocalActivityStub(
            TestActivities.NoArgsActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .build());

    @Override
    public void execute() {
      activity1.execute();
    }
  }

  public static class ApplicationFailureActivity implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {
      throw new Error("test");
    }
  }
}
