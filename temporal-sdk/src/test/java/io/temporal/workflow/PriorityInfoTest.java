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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.Priority;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PriorityInfoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestPriority.class, TestPriorityChildWorkflow.class)
          .setActivityImplementations(new PriorityActivitiesImpl())
          .build();

  @Test
  public void testPriority() {
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setPriority(Priority.newBuilder().setPriorityKey(5).build())
                    .build());
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals("5", result);
  }

  @ActivityInterface
  public interface PriorityActivities {
    String activity1(String a1);
  }

  public static class PriorityActivitiesImpl implements PriorityActivities {
    @Override
    public String activity1(String a1) {
      return String.valueOf(
          Activity.getExecutionContext().getInfo().getPriority().getPriorityKey());
    }
  }

  public static class TestPriorityChildWorkflow implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      return String.valueOf(Workflow.getInfo().getPriority().getPriorityKey());
    }
  }

  public static class TestPriority implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      // Test that the priority is passed to activities
      String priority =
          Workflow.newActivityStub(
                  PriorityActivities.class,
                  ActivityOptions.newBuilder()
                      .setTaskQueue(taskQueue)
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setPriority(Priority.newBuilder().setPriorityKey(3).build())
                      .setDisableEagerExecution(true)
                      .build())
              .activity1("1");
      Assert.assertEquals("3", priority);
      // Test that of if no priority is set the workflows priority is used
      priority =
          Workflow.newActivityStub(
                  PriorityActivities.class,
                  ActivityOptions.newBuilder()
                      .setTaskQueue(taskQueue)
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setDisableEagerExecution(true)
                      .build())
              .activity1("2");
      Assert.assertEquals("5", priority);
      // Test that of if a default priority is set the workflows priority is used
      priority =
          Workflow.newActivityStub(
                  PriorityActivities.class,
                  ActivityOptions.newBuilder()
                      .setTaskQueue(taskQueue)
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setPriority(Priority.newBuilder().build())
                      .setDisableEagerExecution(true)
                      .build())
              .activity1("2");
      Assert.assertEquals("5", priority);
      // Test that the priority is passed to child workflows
      priority =
          Workflow.newChildWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  ChildWorkflowOptions.newBuilder()
                      .setPriority(Priority.newBuilder().setPriorityKey(1).build())
                      .build())
              .execute();
      Assert.assertEquals("1", priority);
      // Test that of no priority is set the workflows priority is used
      priority =
          Workflow.newChildWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  ChildWorkflowOptions.newBuilder().build())
              .execute();
      Assert.assertEquals("5", priority);
      // Test that if a default priority is set the workflows priority is used
      priority =
          Workflow.newChildWorkflowStub(
                  TestWorkflows.TestWorkflowReturnString.class,
                  ChildWorkflowOptions.newBuilder()
                      .setPriority(Priority.newBuilder().build())
                      .build())
              .execute();
      Assert.assertEquals("5", priority);
      // Return the workflows priority
      return String.valueOf(Workflow.getInfo().getPriority().getPriorityKey());
    }
  }
}
