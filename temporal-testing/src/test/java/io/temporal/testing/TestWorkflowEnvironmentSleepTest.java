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

package io.temporal.testing;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class TestWorkflowEnvironmentSleepTest {

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnv != null) {
            System.err.println(testEnv.getDiagnostics());
            testEnv.close();
          }
        }
      };

  @WorkflowInterface
  public interface ExampleWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void signal();
  }

  public static class ExampleWorkflowImpl implements ExampleWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofMinutes(20));
    }

    @Override
    public void signal() {}
  }

  private TestWorkflowEnvironment testEnv;
  private Worker worker;
  private WorkflowClient client;
  private ExampleWorkflow workflow;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(ExampleWorkflowImpl.class);
    workflow =
        client.newWorkflowStub(
            ExampleWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    testEnv.start();
  }

  @Test(timeout = 2000)
  public void testSignalAfterStartThenSleep() {
    WorkflowClient.start(workflow::execute);
    workflow.signal();
    testEnv.sleep(Duration.ofMinutes(50L));
  }

  @After
  public void tearDown() {
    testEnv.close();
  }
}
