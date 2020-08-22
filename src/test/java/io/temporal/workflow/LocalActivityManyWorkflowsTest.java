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

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LocalActivityManyWorkflowsTest {

  private static final String TASK_QUEUE = "test-workflow";

  private TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class ActivityWorkflow implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newLocalActivityStub(
            TestActivity.class, LocalActivityOptions.newBuilder().build());

    @Override
    public String workflow(String input) {
      return activity.activity(input + "3");
    }
  }

  @ActivityInterface
  public interface TestActivity {
    String activity(String input);
  }

  private static class ActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      return input + "1";
    }
  }

  @Test
  public void manyWorkflowsTest() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new ActivityImpl());

    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();

    for (int reqCount = 1; reqCount < 1000; reqCount++) {
      TestWorkflow workflow = client.newWorkflowStub(TestWorkflow.class, options);
      String input = String.valueOf(reqCount);
      String result = workflow.workflow(input);
      assertEquals(input + "31", result);
    }
  }
}
