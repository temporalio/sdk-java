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

import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.NonSerializableException;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonSerializableExceptionInActivityWorkflowTest {

  private final NonSerializableExceptionActivityImpl activitiesImpl =
      new NonSerializableExceptionActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNonSerializableExceptionInActivityWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testNonSerializableExceptionInActivity() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertTrue(result.contains("NonSerializableException"));
  }

  public static class NonSerializableExceptionActivityImpl implements NoArgsActivity {

    @Override
    public void execute() {
      throw new NonSerializableException();
    }
  }

  public static class TestNonSerializableExceptionInActivityWorkflow implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      NoArgsActivity activity =
          Workflow.newActivityStub(
              NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(5))
                  .build());
      try {
        activity.execute();
      } catch (ActivityFailure e) {
        return e.getCause().getMessage();
      }
      return "done";
    }
  }
}
