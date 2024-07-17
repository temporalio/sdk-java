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

package io.temporal.internal.worker;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

// Verifies using the worker options without an explicit slot supplier still does the right thing
public class WorkflowSlotMaxConcurrentTests {
  private static final int MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 2;
  private static final int MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 2;
  private static final int MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 2;
  private final TestStatsReporter reporter = new TestStatsReporter();
  static AtomicInteger concurrentActivityHighMark = new AtomicInteger();
  static AtomicInteger concurrentLocalActivityHighMark = new AtomicInteger();

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setMaxConcurrentWorkflowTaskExecutionSize(
                      MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE)
                  .setMaxConcurrentActivityExecutionSize(MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE)
                  .setMaxConcurrentLocalActivityExecutionSize(
                      MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE)
                  .build())
          .setMetricsScope(metricsScope)
          .setActivityImplementations(new TestActivityImpl())
          .setWorkflowTypes(SleepingWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  @Before
  public void setup() {
    reporter.flush();
    concurrentActivityHighMark.set(0);
    concurrentLocalActivityHighMark.set(0);
  }

  @After
  public void tearDown() {
    testWorkflowRule.getTestEnvironment().close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String action);
  }

  public static class SleepingWorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    private final TestActivity localActivity =
        Workflow.newLocalActivityStub(
            TestActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(String action) {
      if (action.equals("local-activity")) {
        localActivity.activity(true);
      } else if (action.equals("activity")) {
        activity.activity(false);
      }
      return "ok";
    }
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    String activity(boolean isLocal);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String activity(boolean isLocal) {
      if (isLocal) {
        int current = concurrentLocalActivityHighMark.incrementAndGet();
        if (current > MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE) {
          throw new IllegalStateException("Too many local activities running concurrently");
        }
      } else {
        int current = concurrentActivityHighMark.incrementAndGet();
        if (current > MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE) {
          throw new IllegalStateException("Too many activities running concurrently");
        }
      }

      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      if (isLocal) {
        concurrentLocalActivityHighMark.decrementAndGet();
      } else {
        concurrentActivityHighMark.decrementAndGet();
      }

      return "";
    }
  }

  @Test
  public void TestSlotsNotExceeded() {
    testWorkflowRule.getTestEnvironment().start();
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .validateBuildWithDefaults();
    // Run a handful of workflows concurrently
    List<WorkflowExecution> executions = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      executions.add(
          WorkflowClient.start(
              client.newWorkflowStub(TestWorkflow.class, workflowOptions)::workflow,
              "local-activity"));
    }
    for (int i = 0; i < 5; i++) {
      executions.add(
          WorkflowClient.start(
              client.newWorkflowStub(TestWorkflow.class, workflowOptions)::workflow, "activity"));
    }

    // wait for all of them to finish
    for (WorkflowExecution execution : executions) {
      WorkflowStub workflowStub = client.newUntypedWorkflowStub(execution, Optional.empty());
      workflowStub.getResult(String.class);
    }
  }
}
