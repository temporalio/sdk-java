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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.NonDeterministicException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowFailedMetricsTests {
  private final TestStatsReporter reporter = new TestStatsReporter();
  private static boolean triggerNonDeterministicException = false;

  Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(1));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(
                      NonDeterministicException.class, IllegalArgumentException.class)
                  .build())
          .setMetricsScope(metricsScope)
          .setWorkflowTypes(
              NonDeterministicWorkflowImpl.class,
              WorkflowExceptionImpl.class,
              ApplicationFailureWorkflowImpl.class)
          .build();

  @Before
  public void setup() {
    reporter.flush();
  }

  @WorkflowInterface
  public interface TestWorkflowWithSignal {
    @WorkflowMethod
    String workflow();

    @SignalMethod
    void unblock();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(boolean runtimeException);
  }

  @WorkflowInterface
  public interface ApplicationFailureWorkflow {
    @WorkflowMethod
    void execute(boolean isBenign);
  }

  public static class NonDeterministicWorkflowImpl implements TestWorkflowWithSignal {
    @Override
    public String workflow() {
      if (triggerNonDeterministicException) {
        Workflow.sleep(Duration.ofSeconds(1));
      }
      Workflow.sideEffect(Integer.class, () -> 0);
      Workflow.await(() -> false);
      return "ok";
    }

    @Override
    public void unblock() {}
  }

  public static class WorkflowExceptionImpl implements TestWorkflow {
    @Override
    public String workflow(boolean runtimeException) {
      if (runtimeException) {
        throw new IllegalArgumentException("test exception");
      } else {
        throw ApplicationFailure.newFailure("test failure", "test reason");
      }
    }
  }

  public static class ApplicationFailureWorkflowImpl implements ApplicationFailureWorkflow {
    @Override
    public void execute(boolean isBenign) {
      if (!isBenign) {
        throw ApplicationFailure.newFailure("Non-benign failure", "NonBenignType");
      } else {
        throw ApplicationFailure.newFailureWithCategory(
            "Benign failure", "BenignType", ApplicationErrorCategory.BENIGN, null);
      }
    }
  }

  private Map<String, String> getWorkflowTags(String workflowType) {
    return ImmutableMap.of(
        "task_queue",
        testWorkflowRule.getTaskQueue(),
        "namespace",
        "UnitTest",
        "workflow_type",
        workflowType);
  }

  @Test
  public void nonDeterminismIncrementsWorkflowFailedMetric() {
    reporter.assertNoMetric(
        MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflowWithSignal"));
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflowWithSignal workflow =
        client.newWorkflowStub(
            TestWorkflowWithSignal.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowExecution exec = WorkflowClient.start(workflow::workflow);
    testWorkflowRule.waitForTheEndOfWFT(exec.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();
    triggerNonDeterministicException = true;
    workflow.unblock();
    assertThrows(WorkflowFailedException.class, () -> workflow.workflow());
    reporter.assertCounter(
        MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflowWithSignal"), 1);
  }

  @Test
  public void runtimeExceptionWorkflowFailedMetric() {
    reporter.assertNoMetric(MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflow"));
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    assertThrows(WorkflowFailedException.class, () -> workflow.workflow(true));
    reporter.assertCounter(MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflow"), 1);
  }

  @Test
  public void applicationFailureWorkflowFailedMetric() {
    reporter.assertNoMetric(MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflow"));
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    assertThrows(WorkflowFailedException.class, () -> workflow.workflow(false));
    reporter.assertCounter(MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("TestWorkflow"), 1);
  }

  @Test
  public void workflowFailureMetricBenignApplicationError() {
    reporter.assertNoMetric(
        MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("ApplicationFailureWorkflow"));

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    ApplicationFailureWorkflow nonBenignStub =
        client.newWorkflowStub(
            ApplicationFailureWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    WorkflowFailedException e1 =
        assertThrows(WorkflowFailedException.class, () -> nonBenignStub.execute(false));

    Throwable cause1 = e1.getCause();
    assertTrue("Cause should be ApplicationFailure", cause1 instanceof ApplicationFailure);
    assertFalse(
        "Failure should not be benign", ApplicationFailure.isBenignApplicationFailure(cause1));
    assertEquals("Non-benign failure", ((TemporalFailure) cause1).getOriginalMessage());

    reporter.assertCounter(
        MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("ApplicationFailureWorkflow"), 1);

    ApplicationFailureWorkflow benignStub =
        client.newWorkflowStub(
            ApplicationFailureWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    WorkflowFailedException e2 =
        assertThrows(WorkflowFailedException.class, () -> benignStub.execute(true));

    Throwable cause2 = e2.getCause();
    assertTrue("Cause should be ApplicationFailure", cause2 instanceof ApplicationFailure);
    assertTrue("Failure should be benign", ApplicationFailure.isBenignApplicationFailure(cause2));
    assertEquals("Benign failure", ((TemporalFailure) cause2).getOriginalMessage());

    reporter.assertCounter(
        MetricsType.WORKFLOW_FAILED_COUNTER, getWorkflowTags("ApplicationFailureWorkflow"), 1);
  }
}
