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

import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionWorkflowRemoveTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWorkflowRemove.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionWorkflowRemove() {
    Assume.assumeFalse("skipping for docker tests", SDKTestWorkflowRule.useExternalService);

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    Assert.assertEquals("foo10", workflowStub.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestGetVersionWorkflowRemove implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      String result;
      // Test adding a version check in replay code.
      if (!Workflow.isReplaying()) {
        // The first version of the code
        int changeFoo = Workflow.getVersion("changeFoo", Workflow.DEFAULT_VERSION, 1);
        if (changeFoo != 1) {
          throw new IllegalStateException("Unexpected version: " + 1);
        }
        result = activities.activity2("foo", 10);
      } else {
        // No getVersionCall
        result = activities.activity2("foo", 10);
      }
      Workflow.sleep(1000); // forces new workflow task
      return result;
    }
  }
}
