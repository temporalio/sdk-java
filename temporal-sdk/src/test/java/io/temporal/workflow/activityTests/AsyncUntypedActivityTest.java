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

import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncUntypedActivityTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncUtypedActivityWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testAsyncUntypedActivity() {
    activitiesImpl.completionClient =
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient();
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("proc", activitiesImpl.procResult.get(0));
    Assert.assertEquals("1", activitiesImpl.procResult.get(1));
    Assert.assertEquals("12", activitiesImpl.procResult.get(2));
    Assert.assertEquals("123", activitiesImpl.procResult.get(3));
    Assert.assertEquals("1234", activitiesImpl.procResult.get(4));
  }

  public static class TestAsyncUtypedActivityWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ActivityStub testActivities =
          Workflow.newUntypedActivityStub(TestOptions.newActivityOptions20sScheduleToClose());
      Promise<String> a = Async.function(testActivities::<String>execute, "Activity", String.class);
      Promise<String> a1 =
          Async.function(
              testActivities::<String>execute,
              "customActivity1",
              String.class,
              "1"); // name overridden in annotation
      Promise<String> a2 =
          Async.function(testActivities::<String>execute, "Activity2", String.class, "1", 2);
      Promise<String> a3 =
          Async.function(testActivities::<String>execute, "Activity3", String.class, "1", 2, 3);
      Promise<String> a4 =
          Async.function(testActivities::<String>execute, "Activity4", String.class, "1", 2, 3, 4);
      assertEquals("activity", a.get());
      assertEquals("1", a1.get());
      assertEquals("12", a2.get());
      assertEquals("123", a3.get());
      assertEquals("1234", a4.get());

      Async.procedure(testActivities::<Void>execute, "Proc", Void.class).get();
      Async.procedure(testActivities::<Void>execute, "Proc1", Void.class, "1").get();
      Async.procedure(testActivities::<Void>execute, "Proc2", Void.class, "1", 2).get();
      Async.procedure(testActivities::<Void>execute, "Proc3", Void.class, "1", 2, 3).get();
      Async.procedure(testActivities::<Void>execute, "Proc4", Void.class, "1", 2, 3, 4).get();
      return "workflow";
    }
  }
}
