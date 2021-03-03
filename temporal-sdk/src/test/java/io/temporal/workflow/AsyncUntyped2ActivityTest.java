/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.TestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncUntyped2ActivityTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncUtypedActivity2WorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  @Test
  public void testAsyncUntyped2Activity() {
    // TODO: (vkoby) See if this activityImpl could be constructed from within the rule with the
    // right completion client.
    activitiesImpl.completionClient =
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient();
    WorkflowTest.TestWorkflow1 client =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                WorkflowTest.TestWorkflow1.class,
                TestOptions.newWorkflowOptionsBuilder(testWorkflowRule.getTaskQueue()).build());
    String result = client.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("proc", activitiesImpl.procResult.get(0));
    Assert.assertEquals("1", activitiesImpl.procResult.get(1));
    Assert.assertEquals("12", activitiesImpl.procResult.get(2));
    Assert.assertEquals("123", activitiesImpl.procResult.get(3));
    Assert.assertEquals("1234", activitiesImpl.procResult.get(4));
    Assert.assertEquals("12345", activitiesImpl.procResult.get(5));
    Assert.assertEquals("123456", activitiesImpl.procResult.get(6));
  }

  public static class TestAsyncUtypedActivity2WorkflowImpl implements WorkflowTest.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityStub testActivities =
          Workflow.newUntypedActivityStub(TestOptions.newActivityOptions20sScheduleToClose());
      Promise<String> a = testActivities.executeAsync("Activity", String.class);
      Promise<String> a1 =
          testActivities.executeAsync(
              "customActivity1", String.class, "1"); // name overridden in annotation
      Promise<String> a2 = testActivities.executeAsync("Activity2", String.class, "1", 2);
      Promise<String> a3 = testActivities.executeAsync("Activity3", String.class, "1", 2, 3);
      Promise<String> a4 = testActivities.executeAsync("Activity4", String.class, "1", 2, 3, 4);
      Promise<String> a5 = testActivities.executeAsync("Activity5", String.class, "1", 2, 3, 4, 5);
      Promise<String> a6 =
          testActivities.executeAsync("Activity6", String.class, "1", 2, 3, 4, 5, 6);
      assertEquals("activity", a.get());
      assertEquals("1", a1.get());
      assertEquals("12", a2.get());
      assertEquals("123", a3.get());
      assertEquals("1234", a4.get());
      assertEquals("12345", a5.get());
      assertEquals("123456", a6.get());

      testActivities.executeAsync("Proc", Void.class).get();
      testActivities.executeAsync("Proc1", Void.class, "1").get();
      testActivities.executeAsync("Proc2", Void.class, "1", 2).get();
      testActivities.executeAsync("Proc3", Void.class, "1", 2, 3).get();
      testActivities.executeAsync("Proc4", Void.class, "1", 2, 3, 4).get();
      testActivities.executeAsync("Proc5", Void.class, "1", 2, 3, 4, 5).get();
      testActivities.executeAsync("Proc6", Void.class, "1", 2, 3, 4, 5, 6).get();
      return "workflow";
    }
  }
}
