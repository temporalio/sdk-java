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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.*;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ActivityTest extends WorkflowTest {

  // TODO: (vkoby): refactor to common fields
  private static final String UUID_REGEXP =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Test
  public void testActivityRetryWithMaxAttempts() {
    startWorkerFor(WorkflowTest.TestActivityRetryWithMaxAttempts.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());

    tracer.setExpected(
        "interceptExecuteWorkflow " + UUID_REGEXP,
        "newThread workflow-method",
        "currentTimeMillis",
        "executeActivity HeartbeatAndThrowIO",
        "activity HeartbeatAndThrowIO",
        "heartbeat 1",
        "activity HeartbeatAndThrowIO",
        "heartbeat 2",
        "activity HeartbeatAndThrowIO",
        "heartbeat 3",
        "currentTimeMillis");
  }

  @Test
  public void testActivityRetryWithExpiration() {
    startWorkerFor(WorkflowTest.TestActivityRetryWithExpiration.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  @Test
  public void testLocalActivityRetry() {
    startWorkerFor(WorkflowTest.TestLocalActivityRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue)
                .setWorkflowTaskTimeout(Duration.ofSeconds(5))
                .build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    Assert.assertEquals("last attempt", 5, activitiesImpl.getLastAttempt());
  }

  @Test
  public void testActivityRetryOnTimeout() {
    startWorkerFor(WorkflowTest.TestActivityRetryOnTimeout.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    // Wall time on purpose
    long start = System.currentTimeMillis();
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(
          String.valueOf(e.getCause()), e.getCause().getCause() instanceof TimeoutFailure);
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    long elapsed = System.currentTimeMillis() - start;
    if (testName.toString().contains("TestService")) {
      Assert.assertTrue("retry timer skips time", elapsed < 5000);
    }
  }

  @Test
  public void testActivityRetryOptionsChange() {
    startWorkerFor(WorkflowTest.TestActivityRetryOptionsChange.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  @Test
  public void testUntypedActivityRetry() {
    startWorkerFor(WorkflowTest.TestUntypedActivityRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  @Test
  public void testActivityRetryAnnotated() {
    startWorkerFor(WorkflowTest.TestActivityRetryAnnotated.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
  }

  @Test
  public void testActivityApplicationFailureRetry() {
    startWorkerFor(WorkflowTest.TestActivityApplicationFailureRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "simulatedType", ((ApplicationFailure) e.getCause().getCause()).getType());
      Assert.assertEquals(
          "io.temporal.failure.ApplicationFailure: message='simulated', type='simulatedType', nonRetryable=true",
          e.getCause().getCause().toString());
    }
    Assert.assertEquals(3, activitiesImpl.applicationFailureCounter.get());
  }

  @Test
  public void testActivityApplicationFailureNonRetryable() {
    startWorkerFor(WorkflowTest.TestActivityApplicationFailureNonRetryable.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "java.io.IOException", ((ApplicationFailure) e.getCause().getCause()).getType());
      Assert.assertEquals(
          RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
          ((ActivityFailure) e.getCause()).getRetryState());
    }
    Assert.assertEquals(activitiesImpl.toString(), 1, activitiesImpl.invocations.size());
  }

  @Test
  public void testActivityApplicationNoSpecifiedRetry() {
    startWorkerFor(WorkflowTest.TestActivityApplicationNoSpecifiedRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "simulatedType", ((ApplicationFailure) e.getCause().getCause()).getType());
    }

    // Since no retry policy is passed by the client, we fall back to the default retry policy of
    // the mock server, which mimics the default on a default Temporal deployment.
    Assert.assertEquals(3, activitiesImpl.applicationFailureCounter.get());
  }

  @Test
  public void testActivityApplicationOptOutOfRetry() {
    startWorkerFor(WorkflowTest.TestActivityApplicationOptOutOfRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          "simulatedType", ((ApplicationFailure) e.getCause().getCause()).getType());
    }

    // Since maximum attempts is set to 1, there should be no retries at all
    Assert.assertEquals(1, activitiesImpl.applicationFailureCounter.get());
  }

  @Test
  public void testAsyncActivityRetry() {
    startWorkerFor(WorkflowTest.TestAsyncActivityRetry.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(
          String.valueOf(e.getCause().getCause()),
          e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    regenerateHistoryForReplay(execution, "testAsyncActivityRetryHistory");
  }

  @Test
  public void testAsyncActivityRetryReplay() throws Exception {
    // Avoid executing 4 times
    Assume.assumeFalse("skipping for docker tests", WorkflowTest.useExternalService);
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testAsyncActivityRetryHistory.json", WorkflowTest.TestAsyncActivityRetry.class);
  }

  @Test
  public void testAsyncActivityRetryOptionsChange() {
    startWorkerFor(WorkflowTest.TestAsyncActivityRetryOptionsChange.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    try {
      workflowStub.execute(taskQueue);
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ActivityFailure);
      Assert.assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    Assert.assertEquals(activitiesImpl.toString(), 2, activitiesImpl.invocations.size());
  }

  @Test
  public void testTryCancelActivity() {
    startWorkerFor(WorkflowTest.TestTryCancelActivity.class);
    WorkflowTest.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowClient.start(client::execute, taskQueue);
    sleep(Duration.ofMillis(500)); // To let activityWithDelay start.
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    waitForOKQuery(stub);
    stub.cancel();
    long start = currentTimeMillis();
    try {
      stub.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    long elapsed = currentTimeMillis() - start;
    Assert.assertTrue(String.valueOf(elapsed), elapsed < 500);
    activitiesImpl.assertInvocations("activityWithDelay");
  }

  @Test
  public void testAbandonOnCancelActivity() {
    startWorkerFor(WorkflowTest.TestAbandonOnCancelActivity.class);
    WorkflowTest.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    WorkflowExecution execution = WorkflowClient.start(client::execute, taskQueue);
    sleep(Duration.ofMillis(500)); // To let activityWithDelay start.
    WorkflowStub stub = WorkflowStub.fromTyped(client);
    waitForOKQuery(stub);
    stub.cancel();
    long start = currentTimeMillis();
    try {
      stub.getResult(String.class);
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      Assert.assertTrue(e.getCause() instanceof CanceledFailure);
    }
    long elapsed = currentTimeMillis() - start;
    Assert.assertTrue(String.valueOf(elapsed), elapsed < 500);
    activitiesImpl.assertInvocations("activityWithDelay");
    GetWorkflowExecutionHistoryRequest request =
        GetWorkflowExecutionHistoryRequest.newBuilder()
            .setNamespace(WorkflowTest.NAMESPACE)
            .setExecution(execution)
            .build();
    GetWorkflowExecutionHistoryResponse response =
        service.blockingStub().getWorkflowExecutionHistory(request);

    for (HistoryEvent event : response.getHistory().getEventsList()) {
      Assert.assertNotEquals(
          EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED, event.getEventType());
    }
  }

  @Test
  public void testAsyncActivity() {
    startWorkerFor(WorkflowTest.TestAsyncActivityWorkflowImpl.class);
    WorkflowTest.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    String result = client.execute(taskQueue);
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("proc", activitiesImpl.procResult.get(0));
    Assert.assertEquals("1", activitiesImpl.procResult.get(1));
    Assert.assertEquals("12", activitiesImpl.procResult.get(2));
    Assert.assertEquals("123", activitiesImpl.procResult.get(3));
    Assert.assertEquals("1234", activitiesImpl.procResult.get(4));
    Assert.assertEquals("12345", activitiesImpl.procResult.get(5));
    Assert.assertEquals("123456", activitiesImpl.procResult.get(6));
  }

  @Test
  public void testAsyncUntypedActivity() {
    startWorkerFor(WorkflowTest.TestAsyncUtypedActivityWorkflowImpl.class);
    WorkflowTest.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    String result = client.execute(taskQueue);
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("proc", activitiesImpl.procResult.get(0));
    Assert.assertEquals("1", activitiesImpl.procResult.get(1));
    Assert.assertEquals("12", activitiesImpl.procResult.get(2));
    Assert.assertEquals("123", activitiesImpl.procResult.get(3));
    Assert.assertEquals("1234", activitiesImpl.procResult.get(4));
  }

  @Test
  public void testAsyncUntyped2Activity() {
    startWorkerFor(WorkflowTest.TestAsyncUtypedActivity2WorkflowImpl.class);
    WorkflowTest.TestWorkflow1 client =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    String result = client.execute(taskQueue);
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("proc", activitiesImpl.procResult.get(0));
    Assert.assertEquals("1", activitiesImpl.procResult.get(1));
    Assert.assertEquals("12", activitiesImpl.procResult.get(2));
    Assert.assertEquals("123", activitiesImpl.procResult.get(3));
    Assert.assertEquals("1234", activitiesImpl.procResult.get(4));
    Assert.assertEquals("12345", activitiesImpl.procResult.get(5));
    Assert.assertEquals("123456", activitiesImpl.procResult.get(6));
  }

  @Test
  public void testNonSerializableExceptionInActivity() {
    worker.registerActivitiesImplementations(
        new WorkflowTest.NonSerializableExceptionActivityImpl());
    startWorkerFor(WorkflowTest.TestNonSerializableExceptionInActivityWorkflow.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());

    String result = workflowStub.execute(taskQueue);
    Assert.assertTrue(result.contains("NonSerializableException"));
  }

  @Test
  public void testNonSerializableArgumentsInActivity() {
    worker.registerActivitiesImplementations(
        new WorkflowTest.NonDeserializableExceptionActivityImpl());
    startWorkerFor(WorkflowTest.TestNonSerializableArgumentsInActivityWorkflow.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());

    String result = workflowStub.execute(taskQueue);
    Assert.assertEquals(
        "ApplicationFailure-io.temporal.common.converter.DataConverterException", result);
  }

  @Test
  public void testLocalActivity() {
    startWorkerFor(WorkflowTest.TestLocalActivityWorkflowImpl.class);
    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(
            WorkflowTest.TestWorkflow1.class,
            WorkflowTest.newWorkflowOptionsBuilder(taskQueue).build());
    String result = workflowStub.execute(taskQueue);
    Assert.assertEquals("test123123", result);
    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    tracer.setExpected(
        "interceptExecuteWorkflow " + UUID_REGEXP,
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

  @Test
  public void testParallelLocalActivities() {
    startWorkerFor(WorkflowTest.TestParallelLocalActivitiesWorkflowImpl.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(5))
            .setWorkflowTaskTimeout(Duration.ofSeconds(3))
            .setTaskQueue(taskQueue)
            .build();

    WorkflowTest.TestWorkflow1 workflowStub =
        workflowClient.newWorkflowStub(WorkflowTest.TestWorkflow1.class, options);
    String result = workflowStub.execute(taskQueue);
    Assert.assertEquals("done", result);
    Assert.assertEquals(activitiesImpl.toString(), 100, activitiesImpl.invocations.size());
    List<String> expected = new ArrayList<String>();
    expected.add("interceptExecuteWorkflow " + UUID_REGEXP);
    expected.add("newThread workflow-method");
    for (int i = 0; i < WorkflowTest.TestParallelLocalActivitiesWorkflowImpl.COUNT; i++) {
      expected.add("executeLocalActivity SleepActivity");
      expected.add("currentTimeMillis");
    }
    for (int i = 0; i < WorkflowTest.TestParallelLocalActivitiesWorkflowImpl.COUNT; i++) {
      expected.add("local activity SleepActivity");
    }
    tracer.setExpected(expected.toArray(new String[0]));
  }

  @Test
  public void testLocalActivityAndQuery() throws ExecutionException, InterruptedException {

    startWorkerFor(WorkflowTest.TestLocalActivityAndQueryWorkflow.class);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofMinutes(30))
            // Large workflow task timeout to avoid workflow task heartbeating
            .setWorkflowTaskTimeout(Duration.ofSeconds(30))
            .setTaskQueue(taskQueue)
            .build();
    WorkflowTest.TestWorkflowQuery workflowStub =
        workflowClient.newWorkflowStub(WorkflowTest.TestWorkflowQuery.class, options);
    WorkflowClient.start(workflowStub::execute, taskQueue);

    // Ensure that query doesn't see intermediate results of the local activities execution
    // as all these activities are executed in a single workflow task.
    while (true) {
      String queryResult = workflowStub.query();
      Assert.assertTrue(queryResult, queryResult.equals("run4"));
      List<ForkJoinTask<String>> tasks = new ArrayList<ForkJoinTask<String>>();
      int threads = 30;
      if (queryResult.equals("run4")) {
        for (int i = 0; i < threads; i++) {
          ForkJoinTask<String> task = ForkJoinPool.commonPool().submit(() -> workflowStub.query());
          tasks.add(task);
        }
        for (int i = 0; i < threads; i++) {
          Assert.assertEquals("run4", tasks.get(i).get());
        }
        break;
      }
    }
    String result = WorkflowStub.fromTyped(workflowStub).getResult(String.class);
    Assert.assertEquals("done", result);
    Assert.assertEquals("run4", workflowStub.query());
    activitiesImpl.assertInvocations(
        "sleepActivity", "sleepActivity", "sleepActivity", "sleepActivity", "sleepActivity");
  }
}
