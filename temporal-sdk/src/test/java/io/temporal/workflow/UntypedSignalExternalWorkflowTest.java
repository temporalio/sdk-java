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

import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UntypedSignalExternalWorkflowTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              TestUntypedSignalExternalWorkflow.class, UntypedSignalingChildImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testUntypedSignalExternalWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(20))
            .setWorkflowTaskTimeout(Duration.ofSeconds(2))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    WorkflowTest.TestWorkflowSignaled client =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowTest.TestWorkflowSignaled.class, options);
    Assert.assertEquals("Hello World!", client.execute());
  }

  public static class TestUntypedSignalExternalWorkflow
      implements WorkflowTest.TestWorkflowSignaled {

    private final ChildWorkflowStub child = Workflow.newUntypedChildWorkflowStub("SignalingChild");

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          child.executeAsync(String.class, "Hello", Workflow.getInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  public static class UntypedSignalingChildImpl implements WorkflowTest.SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowId) {
      ExternalWorkflowStub parent = Workflow.newUntypedExternalWorkflowStub(parentWorkflowId);
      parent.signal("testSignal", "World");
      return greeting;
    }
  }
}
