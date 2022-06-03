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

package io.temporal.testing;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class TestWorkflowEnvironmentInitialTimeTest {

  private static final Instant WORKFLOW_START_TIME = Instant.parse("2020-01-01T00:00:00Z");
  private static final Duration WORKFLOW_DURATION = Duration.ofHours(10);
  private static final Instant WORKFLOW_END_TIME = Instant.parse("2020-01-01T10:00:00Z");

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
  public interface TimeDependentWorkflow {
    @WorkflowMethod
    Instant waitAndCheckTime(Duration waitDuration);
  }

  public static class TimeDependentWorkflowImpl implements TimeDependentWorkflow {
    @Override
    public Instant waitAndCheckTime(Duration waitDuration) {
      Workflow.sleep(waitDuration);
      return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
  }

  private TestWorkflowEnvironment testEnv;
  private TimeDependentWorkflow workflow;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {

    TestEnvironmentOptions testEnvOptions =
        TestEnvironmentOptions.newBuilder().setInitialTime(WORKFLOW_START_TIME).build();
    testEnv = TestWorkflowEnvironment.newInstance(testEnvOptions);

    Worker worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(TimeDependentWorkflowImpl.class);

    WorkflowClient client = testEnv.getWorkflowClient();
    workflow =
        client.newWorkflowStub(
            TimeDependentWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(WORKFLOW_TASK_QUEUE).build());
    testEnv.start();
  }

  @Test(timeout = 2000)
  public void testSignalAfterStartThenSleep() {
    Instant workflowEndTime = workflow.waitAndCheckTime(WORKFLOW_DURATION);
    assertEquals(WORKFLOW_END_TIME, workflowEndTime.truncatedTo(ChronoUnit.HOURS));
  }

  @After
  public void tearDown() {
    testEnv.close();
  }
}
