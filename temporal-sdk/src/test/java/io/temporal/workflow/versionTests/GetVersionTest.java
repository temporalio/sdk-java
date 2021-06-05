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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersion() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("activity22activity1activity1activity1", result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "getVersion",
            "executeActivity Activity2",
            "activity Activity2",
            "getVersion",
            "executeActivity customActivity1",
            "activity customActivity1",
            "executeActivity customActivity1",
            "activity customActivity1",
            "sleep PT1S",
            "getVersion",
            "executeActivity customActivity1",
            "activity customActivity1");
  }

  public static class TestGetVersionWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      assertEquals(version, 1);
      String result = testActivities.activity2("activity2", 2);

      // Test version change in non-replay code.
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);

      boolean replaying = false;
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        result += "activity" + testActivities.activity1(1); // This is executed in non-replay mode.
      } else {
        replaying = true;
        int version2 = Workflow.getVersion("test_change_2", Workflow.DEFAULT_VERSION, 1);
        assertEquals(version2, Workflow.DEFAULT_VERSION);
        result += "activity" + testActivities.activity1(1);
      }

      // Test get version in replay mode.
      Workflow.sleep(1000);
      version = Workflow.getVersion("test_change", 1, 2);
      assertEquals(version, 1);
      result += "activity" + testActivities.activity1(1);
      assertTrue(replaying);
      return result;
    }
  }
}
