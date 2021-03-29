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

package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalExternalWorkflowTest {
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalExternalWorkflow.class, SignalingChildImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkerInterceptors(
              new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace()))
          .build();

  @Test
  public void testSignalExternalWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(2000))
            .setWorkflowTaskTimeout(Duration.ofSeconds(60))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    WorkflowTest.TestWorkflowSignaled client =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(WorkflowTest.TestWorkflowSignaled.class, options);
    Assert.assertEquals("Hello World!", client.execute());
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + stub.getExecution().getWorkflowId(),
            "registerSignalHandlers testSignal",
            "newThread workflow-method",
            "executeChildWorkflow SignalingChild",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP, // child
            "newThread workflow-method",
            "signalExternalWorkflow " + SDKTestWorkflowRule.UUID_REGEXP + " testSignal",
            "handleSignal testSignal");
  }

  public static class TestSignalExternalWorkflow implements WorkflowTest.TestWorkflowSignaled {

    private final WorkflowTest.SignalingChild child =
        Workflow.newChildWorkflowStub(WorkflowTest.SignalingChild.class);

    private final CompletablePromise<Object> fromSignal = Workflow.newPromise();

    @Override
    public String execute() {
      Promise<String> result =
          Async.function(child::execute, "Hello", Workflow.getInfo().getWorkflowId());
      return result.get() + " " + fromSignal.get() + "!";
    }

    @Override
    public void signal1(String arg) {
      fromSignal.complete(arg);
    }
  }

  public static class SignalingChildImpl implements WorkflowTest.SignalingChild {

    @Override
    public String execute(String greeting, String parentWorkflowId) {
      WorkflowExecution parentExecution =
          WorkflowExecution.newBuilder().setWorkflowId(parentWorkflowId).build();
      WorkflowTest.TestWorkflowSignaled parent =
          Workflow.newExternalWorkflowStub(
              WorkflowTest.TestWorkflowSignaled.class, parentExecution);
      ExternalWorkflowStub untyped = ExternalWorkflowStub.fromTyped(parent);
      //  Same as parent.signal1("World");
      untyped.signal("testSignal", "World");
      return greeting;
    }
  }
}
