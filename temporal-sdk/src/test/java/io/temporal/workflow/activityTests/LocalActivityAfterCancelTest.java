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

import static org.junit.Assert.assertThrows;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.TemporalFailure;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityAfterCancelTest {
  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(TestLocalActivityRetry.class, BlockingWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void localActivityAfterChildWorkflowCanceled() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowClient.execute(workflowStub::execute, "sada");
    WorkflowStub.fromTyped(workflowStub).cancel();
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("sada"));
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED, exception.getWorkflowCloseEventType());
  }

  @Test
  public void testLocalActivityAfterChildWorkflowCanceledReplay() {
    assertThrows(
        RuntimeException.class,
        () ->
            WorkflowReplayer.replayWorkflowExecutionFromResource(
                "testLocalActivityAfterCancelTest.json",
                LocalActivityAfterCancelTest.TestLocalActivityRetry.class));
  }

  @WorkflowInterface
  public static class BlockingWorkflow implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      Workflow.await(() -> false);
      return "";
    }
  }

  public static class TestLocalActivityRetry implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      try {
        ChildWorkflowOptions childOptions =
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-child1")
                .setCancellationType(ChildWorkflowCancellationType.WAIT_CANCELLATION_REQUESTED)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                .validateAndBuildWithDefaults();
        TestWorkflows.TestWorkflowReturnString child =
            Workflow.newChildWorkflowStub(
                TestWorkflows.TestWorkflowReturnString.class, childOptions);
        child.execute();
      } catch (TemporalFailure e) {
        if (CancellationScope.current().isCancelRequested()) {
          Workflow.newDetachedCancellationScope(
                  () -> {
                    VariousTestActivities act =
                        Workflow.newLocalActivityStub(
                            VariousTestActivities.class,
                            LocalActivityOptions.newBuilder()
                                .setStartToCloseTimeout(Duration.ofSeconds(5))
                                .validateAndBuildWithDefaults());
                    act.activity1(10);
                  })
              .run();
          throw e;
        }
      }
      return "dsadsa";
    }
  }
}
