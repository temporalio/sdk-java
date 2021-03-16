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
import static org.junit.Assert.assertFalse;

import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class AwaitTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAwait.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testAwait() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(" awoken i=1 loop i=1 awoken i=2 loop i=2 awoken i=3", result);
  }

  public static class TestAwait implements TestWorkflows.TestWorkflow1 {

    private int i;
    private int j;

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      Async.procedure(
          () -> {
            while (true) {
              Workflow.await(() -> i > j);
              result.append(" awoken i=" + i);
              j++;
            }
          });

      for (i = 1; i < 3; i++) {
        Workflow.await(() -> j >= i);
        result.append(" loop i=" + i);
      }
      assertFalse(Workflow.await(Duration.ZERO, () -> false));
      return result.toString();
    }
  }
}
