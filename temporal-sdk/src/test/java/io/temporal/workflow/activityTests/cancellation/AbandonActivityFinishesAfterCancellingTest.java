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

package io.temporal.workflow.activityTests.cancellation;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test verifies that an Activity started with {@link ActivityCancellationType#ABANDON} can be
 * canceled after starting by the workflow and can successfully finish later. And this combination
 * doesn't cause a problem with workflow state machines.
 */
public class AbandonActivityFinishesAfterCancellingTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAbandonOnCancelActivity.class)
          .setActivityImplementations(new AbandonButFinishingActivity())
          .build();

  @Test
  public void testAbandonOnCancelActivitySuccessfullyFinishesAfterCancelling() {
    TestWorkflows.NoArgsWorkflow client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(client::execute);
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    stub.getResult(Void.class);
  }

  public static class AbandonButFinishingActivity
      implements TestActivities.NoArgsReturnsStringActivity {
    @Override
    public String execute() {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return "done";
    }
  }

  public static class TestAbandonOnCancelActivity implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      TestActivities.NoArgsReturnsStringActivity activity =
          Workflow.newActivityStub(
              TestActivities.NoArgsReturnsStringActivity.class,
              ActivityOptions.newBuilder(
                      SDKTestOptions.newActivityOptionsForTaskQueue(
                          Workflow.getInfo().getTaskQueue()))
                  .setCancellationType(ActivityCancellationType.ABANDON)
                  .build());
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> Async.function(activity::execute));
      cancellationScope.run();
      Workflow.sleep(200); // End of the WFT
      cancellationScope.cancel();
      // to don't let time skipping if enabled to finish the workflow before the first activity is
      // finished
      activity.execute(); // End of the WFT
    }
  }
}
