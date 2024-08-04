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
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class UpdateWithLocalActivityInTheLastWorkflowTaskTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowWithUpdateImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Test
  @Parameters({"true", "false"})
  public void testUpdateWithLocalActivityInTheLastWorkflowTask(Boolean waitOnLA) {
    WorkflowWithUpdate client = testWorkflowRule.newWorkflowStub(WorkflowWithUpdate.class);

    WorkflowStub.fromTyped(client).start(true);
    Thread asyncUpdate =
        new Thread(
            () -> {
              try {
                client.update(waitOnLA);
              } catch (Exception e) {
              }
            });
    asyncUpdate.start();
    assertEquals("done", client.execute(true));
    asyncUpdate.interrupt();
  }

  @WorkflowInterface
  public interface WorkflowWithUpdate {

    @WorkflowMethod
    String execute(Boolean finish);

    @UpdateMethod
    String update(Boolean waitOnLA);
  }

  public static class WorkflowWithUpdateImpl implements WorkflowWithUpdate {
    boolean finish = false;
    private final TestActivities.VariousTestActivities activities =
        Workflow.newLocalActivityStub(
            TestActivities.VariousTestActivities.class,
            LocalActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute(Boolean wait) {
      if (wait) {
        Workflow.await(() -> finish);
      }
      return "done";
    }

    @Override
    public String update(Boolean waitOnLA) {
      if (waitOnLA) {
        Promise promise = Async.procedure(activities::sleepActivity, (long) 10, 0);
        Async.procedure(activities::sleepActivity, (long) 10000, 0);
        promise.get();
      }

      finish = true;
      activities.sleepActivity(1000, 0);
      return "update";
    }
  }
}
