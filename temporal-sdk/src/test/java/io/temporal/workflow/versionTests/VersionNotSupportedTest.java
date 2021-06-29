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
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class VersionNotSupportedTest {

  private static boolean hasReplayed;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestVersionNotSupportedWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testVersionNotSupported() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertEquals(
          "message='unsupported change version', type='test', nonRetryable=false",
          e.getCause().getMessage());
    }
    assertTrue(hasReplayed);
  }

  public static class TestVersionNotSupportedWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      hasReplayed = Workflow.isReplaying();
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));

      // Test adding a version check in non-replay code.
      int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
      String result = "";
      if (version == Workflow.DEFAULT_VERSION) {
        result += "activity" + testActivities.activity1(1);
      } else {
        result += testActivities.activity2("activity2", 2); // This is executed.
      }

      // Catching error from getVersion is only for unit test purpose.
      // Do not ever do it in production code.
      try {
        Workflow.getVersion("test_change", 2, 3);
      } catch (Error e) {
        throw Workflow.wrap(ApplicationFailure.newFailure("unsupported change version", "test"));
      }
      return result;
    }
  }
}
