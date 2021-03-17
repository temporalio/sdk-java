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

package io.temporal.workflow.childWorkflowTests;

import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowTest;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonSerializableExceptionInChildWorkflowTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(WorkflowTest.NonSerializableException.class)
                  .build(),
              TestNonSerializableExceptionInChildWorkflow.class,
              NonSerializableExceptionChildWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testNonSerializableExceptionInChildWorkflow() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertTrue(result.contains("NonSerializableException"));
  }

  public static class NonSerializableExceptionChildWorkflowImpl
      implements WorkflowTest.NonSerializableExceptionChildWorkflow {

    @Override
    public String execute(String taskQueue) {
      throw new WorkflowTest.NonSerializableException();
    }
  }

  public static class TestNonSerializableExceptionInChildWorkflow
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      WorkflowTest.NonSerializableExceptionChildWorkflow child =
          Workflow.newChildWorkflowStub(WorkflowTest.NonSerializableExceptionChildWorkflow.class);
      try {
        child.execute(taskQueue);
      } catch (ChildWorkflowFailure e) {
        return e.getMessage();
      }
      return "done";
    }
  }
}
