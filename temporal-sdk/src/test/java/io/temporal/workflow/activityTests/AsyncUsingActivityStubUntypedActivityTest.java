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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncUsingActivityStubUntypedActivityTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncUsingActivityStubUntypedActivityWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void usingActivityStub() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
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

  public static class TestAsyncUsingActivityStubUntypedActivityWorkflowImpl
      implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityStub testActivities =
          Workflow.newUntypedActivityStub(SDKTestOptions.newActivityOptions20sScheduleToClose());
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
