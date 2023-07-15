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

package io.temporal.workflow.versionTests;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.Issue;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/** This test reproduces a clash in cancellation scopes with getVersion described here: */
@Issue("https://github.com/temporalio/sdk-java/issues/648")
public class GetVersionAfterScopeCancellationInMainWorkflowMethodTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(WorkflowImpl.class).build();

  public static final class WorkflowImpl implements TestWorkflows.NoArgsWorkflow {

    @Override
    public void execute() {
      CancellationScope activeScope1 =
          Workflow.newCancellationScope(() -> Workflow.newTimer(Duration.ofHours(4)));
      activeScope1.run();

      Workflow.getVersion("some-change", Workflow.DEFAULT_VERSION, 1);

      activeScope1.cancel();

      // it's critical for this duration to be short for the test to fail originally (4s or less)
      Duration secondScopeTimerDuration = Duration.ofSeconds(4);
      Workflow.newTimer(secondScopeTimerDuration);

      // this triggers a replay in this test
      // TODO this triggers a replay in this test. While it doesn't appear to be correct
      // That's why there is an alternative testGetVersionAndCancelTimer_replay of this test to make
      // sure
      // that this test suite continues to test what it supposed to test even if this replay
      // behavior is changed or fixed
      Workflow.sleep(Duration.ofSeconds(6));
    }
  }

  @Test
  public void testGetVersionAndCancelTimer() {
    TestWorkflows.NoArgsWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.NoArgsWorkflow.class);

    WorkflowClient.start(workflowStub::execute);

    WorkflowStub untypedWorkflowStub = WorkflowStub.fromTyped(workflowStub);
    untypedWorkflowStub.getResult(Void.TYPE);

    testWorkflowRule.assertNoHistoryEvent(
        untypedWorkflowStub.getExecution().getWorkflowId(), EVENT_TYPE_WORKFLOW_TASK_FAILED);
  }

  @Test
  public void testGetVersionAndCancelTimerReplay() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "get_version_after_scope_cancellation.json", testWorkflowRule.getWorker());
  }
}
