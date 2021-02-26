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
import static org.junit.Assert.assertTrue;

import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowRule;
import io.temporal.testing.TracingWorkerInterceptor;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityTest {

  private final WorkflowTest.TestActivitiesImpl activitiesImpl =
      new WorkflowTest.TestActivitiesImpl(null);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkerInterceptors(
              new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace()))
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  @Test
  public void testLocalActivity() {
    WorkflowTest.TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                WorkflowTest.TestWorkflow1.class,
                testWorkflowRule
                    .newWorkflowOptionsBuilder(testWorkflowRule.getTaskQueue())
                    .build());
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("test123123", result);
    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + testWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeLocalActivity ThrowIO",
            "currentTimeMillis",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "executeLocalActivity Activity2",
            "currentTimeMillis",
            "local activity Activity2",
            "executeActivity Activity2",
            "activity Activity2");
  }

  public static class TestLocalActivityWorkflowImpl implements WorkflowTest.TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      WorkflowTest.TestActivities localActivities =
          Workflow.newLocalActivityStub(
              WorkflowTest.TestActivities.class, WorkflowTest.newLocalActivityOptions1());
      try {
        localActivities.throwIO();
      } catch (ActivityFailure e) {
        try {
          assertTrue(e.getMessage().contains("ThrowIO"));
          assertTrue(e.getCause() instanceof ApplicationFailure);
          assertEquals(IOException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
          assertEquals(
              "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
              e.getCause().getMessage());
        } catch (AssertionError ae) {
          // Errors cause workflow task to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
      }

      String laResult = localActivities.activity2("test", 123);
      WorkflowTest.TestActivities normalActivities =
          Workflow.newActivityStub(
              WorkflowTest.TestActivities.class, WorkflowTest.newActivityOptions1(taskQueue));
      laResult = normalActivities.activity2(laResult, 123);
      return laResult;
    }
  }
}
