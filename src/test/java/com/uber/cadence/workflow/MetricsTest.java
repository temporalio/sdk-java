/*
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

package com.uber.cadence.workflow;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestEnvironmentOptions.Builder;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.tally.Stopwatch;
import java.time.Duration;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class MetricsTest {
  private static final String taskList = "metrics-test";

  public interface TestWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestMetricsInWorkflow implements TestWorkflow {
    @Override
    public void execute() {
      Workflow.getMetricsScope().counter("test-started").inc(1);

      ChildWorkflowOptions options =
          new ChildWorkflowOptions.Builder().setTaskList(taskList).build();
      TestChildWorkflow workflow = Workflow.newChildWorkflowStub(TestChildWorkflow.class, options);
      workflow.executeChild();

      Workflow.getMetricsScope().counter("test-done").inc(1);
    }
  }

  public interface TestChildWorkflow {
    @WorkflowMethod
    void executeChild();
  }

  public static class TestMetricsInChildWorkflow implements TestChildWorkflow {

    @Override
    public void executeChild() {
      Workflow.getMetricsScope().counter("test-child-started").inc(1);

      Stopwatch sw = Workflow.getMetricsScope().timer("test-timer").start();
      Workflow.sleep(3000);
      sw.stop();

      Workflow.getMetricsScope().counter("test-child-done").inc(1);
    }
  }

  @Test
  public void testWorkflowMetrics() throws InterruptedException {
    StatsReporter reporter = mock(StatsReporter.class);
    Scope scope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10));

    TestEnvironmentOptions testOptions =
        new Builder().setDomain(WorkflowTest.DOMAIN).setMetricsScope(scope).build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    Worker worker = env.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(
        TestMetricsInWorkflow.class, TestMetricsInChildWorkflow.class);
    worker.start();

    WorkflowClient workflowClient = env.newWorkflowClient();
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();
    TestWorkflow workflow = workflowClient.newWorkflowStub(TestWorkflow.class, options);
    workflow.execute();

    Thread.sleep(20);

    verify(reporter, times(1)).reportCounter("test-started", null, 1);
    verify(reporter, times(1)).reportCounter("test-done", null, 1);
    verify(reporter, times(1)).reportCounter("test-child-started", null, 1);
    verify(reporter, times(1)).reportCounter("test-child-done", null, 1);

    ArgumentCaptor<com.uber.m3.util.Duration> sleepDurationCaptor =
        ArgumentCaptor.forClass(com.uber.m3.util.Duration.class);
    verify(reporter, times(1)).reportTimer(eq("test-timer"), any(), sleepDurationCaptor.capture());

    com.uber.m3.util.Duration sleepDuration = sleepDurationCaptor.getValue();
    assertTrue(sleepDuration.compareTo(com.uber.m3.util.Duration.ofSeconds(3)) > 0);
    assertTrue(sleepDuration.compareTo(com.uber.m3.util.Duration.ofMillis(3100)) < 0);
  }
}
