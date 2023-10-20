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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class UpdateWithLocalActivityInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSimpleWorkflowWithUpdateImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  public void testUpdateWithLocalActivityInTheLastWorkflowTask() throws InterruptedException {
    TestWorkflows.SimpleWorkflowWithUpdate client =
        testWorkflowRule.newWorkflowStub(TestWorkflows.SimpleWorkflowWithUpdate.class);

    WorkflowStub.fromTyped(client).start();
    Thread asyncUpdate =
        new Thread(
            () -> {
              try {
                System.out.println("Sending update");
                client.update("Update");
              } catch (Exception e) {
              }
            });
    asyncUpdate.start();
    assertEquals("done", client.execute());
    asyncUpdate.interrupt();
  }

  public static class TestSimpleWorkflowWithUpdateImpl
      implements TestWorkflows.SimpleWorkflowWithUpdate {
    Boolean finish = false;

    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute() {
      Workflow.await(() -> finish);
      return "done";
    }

    @Override
    public String update(String value) {
      finish = true;
      activities.sleepActivity(1000, 0);
      return "update";
    }
  }
}
