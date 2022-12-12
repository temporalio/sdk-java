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

package io.temporal.internal.testing;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.ReplayResults;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.rules.Timeout;

public class WorkflowReplayerTest {
  @Rule public Timeout testTimeout = Timeout.seconds(10);

  private TestWorkflowEnvironment testEnvironment;
  private static final String TASK_QUEUE = "workflow-replay-test";
  private List<WorkflowExecutionHistory> histories;

  @Before
  public void setUp() {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);

    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(
        TestWorkflowA.class, TestWorkflowB.class, TestWorkflowC.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions wfOpts = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();

    List<CompletableFuture<Void>> futs = new ArrayList<>();
    for (Class<?> clazz :
        new Class[] {TestWorkflows.NoArgsWorkflow.class, WorkflowB.class, WorkflowC.class}) {
      for (int i = 0; i < 5; i++) {
        WorkflowStub stub = WorkflowStub.fromTyped(client.newWorkflowStub(clazz, wfOpts));
        stub.start();
        futs.add(stub.getResultAsync(Void.class));
      }
    }
    futs.forEach(CompletableFuture::join);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        testEnvironment.getWorkflowServiceStubs().blockingStub();
    ListClosedWorkflowExecutionsResponse resp =
        blockingStub.listClosedWorkflowExecutions(
            ListClosedWorkflowExecutionsRequest.newBuilder().build());
    histories =
        resp.getExecutionsList().stream()
            .map(
                (info) -> {
                  GetWorkflowExecutionHistoryResponse weh =
                      blockingStub.getWorkflowExecutionHistory(
                          GetWorkflowExecutionHistoryRequest.newBuilder()
                              .setNamespace(testEnvironment.getNamespace())
                              .setExecution(info.getExecution())
                              .build());
                  return new WorkflowExecutionHistory(
                      weh.getHistory(), info.getExecution().getWorkflowId());
                })
            .collect(Collectors.toList());

    Assert.assertEquals(15, histories.size());
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testMultipleHistoryReplayOk() throws Exception {
    WorkflowReplayer.replayWorkflowExecutions(
        histories, true, TestWorkflowA.class, TestWorkflowB.class, TestWorkflowC.class);
  }

  @Test(expected = RuntimeException.class)
  public void testMultipleHistoryReplayFailFast() throws Exception {
    WorkflowReplayer.replayWorkflowExecutions(
        histories, true, TestWorkflowAIncompatible.class, TestWorkflowB.class, TestWorkflowC.class);
  }

  @Test
  public void testMultipleHistoryReplayFailSlow() throws Exception {
    ReplayResults results =
        WorkflowReplayer.replayWorkflowExecutions(
            histories,
            false,
            TestWorkflowAIncompatible.class,
            TestWorkflowB.class,
            TestWorkflowC.class);
    Assert.assertTrue(results.hadAnyError());
    Collection<ReplayResults.ReplayError> errors = results.allErrors();
    Assert.assertEquals(5, errors.size());
  }

  public static class TestWorkflowA implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(100);
    }
  }

  public static class TestWorkflowAIncompatible implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {}
  }

  @WorkflowInterface
  public interface WorkflowB {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface WorkflowC {
    @WorkflowMethod
    void execute();
  }

  public static class TestWorkflowB implements WorkflowB {
    @Override
    public void execute() {
      NoArgsActivity activity =
          Workflow.newActivityStub(
              NoArgsActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }

  public static class TestWorkflowC implements WorkflowC {
    @Override
    public void execute() {
      NoArgsActivity activity =
          Workflow.newLocalActivityStub(
              NoArgsActivity.class,
              LocalActivityOptions.newBuilder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(1))
                  .build());
      activity.execute();
    }
  }

  public static class TestActivityImpl implements NoArgsActivity {
    @Override
    public void execute() {}
  }
}
