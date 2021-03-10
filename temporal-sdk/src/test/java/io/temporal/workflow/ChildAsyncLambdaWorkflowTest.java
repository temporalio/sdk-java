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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ChildAsyncLambdaWorkflowTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWaitOnSignalWorkflowImpl.class, TestChildAsyncLambdaWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkerInterceptors(
              new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace()))
          .build();

  /**
   * The purpose of this test is to exercise the lambda execution logic inside Async.procedure(),
   * which executes on a different thread than workflow-main. This is different than executing
   * classes that implements the workflow method interface, which executes on the workflow main
   * thread.
   */
  @Test
  public void testChildAsyncLambdaWorkflow() {
    WorkflowOptions.Builder options = WorkflowOptions.newBuilder();
    options.setWorkflowRunTimeout(Duration.ofSeconds(200));
    options.setWorkflowTaskTimeout(Duration.ofSeconds(60));
    options.setTaskQueue(testWorkflowRule.getTaskQueue());
    TestWorkflows.TestWorkflow1 client =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflow1.class, options.build());
    Assert.assertEquals(null, client.execute(testWorkflowRule.getTaskQueue()));
  }

  public static class TestWaitOnSignalWorkflowImpl implements WorkflowTest.WaitOnSignalWorkflow {

    private final CompletablePromise<String> signal = Workflow.newPromise();

    @Override
    public void execute() {
      signal.get();
    }

    @Override
    public void signal(String value) {
      Workflow.sleep(Duration.ofSeconds(1));
      signal.complete(value);
    }
  }

  public static class TestChildAsyncLambdaWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      ChildWorkflowOptions workflowOptions =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowRunTimeout(Duration.ofSeconds(100))
              .setWorkflowTaskTimeout(Duration.ofSeconds(60))
              .setTaskQueue(taskQueue)
              .build();

      WorkflowTest.WaitOnSignalWorkflow child =
          Workflow.newChildWorkflowStub(WorkflowTest.WaitOnSignalWorkflow.class, workflowOptions);
      Promise<Void> promise = Async.procedure(child::execute);
      Promise<WorkflowExecution> executionPromise = Workflow.getWorkflowExecution(child);
      assertNotNull(executionPromise);
      WorkflowExecution execution = executionPromise.get();
      assertNotEquals("", execution.getWorkflowId());
      assertNotEquals("", execution.getRunId());
      child.signal("test");

      promise.get();
      return null;
    }
  }
}
