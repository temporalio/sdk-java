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

package io.temporal.client.functional;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Stopwatch;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class UpdateTestTimeout {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(UpdateTestTimeout.BlockingWorkflowImpl.class)
          .build();

  @Test
  public void closeWorkflowWhileUpdateIsRunning() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = BlockingWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    // Send an update that is accepted, but will not complete.
    WorkflowUpdateHandle<String> handle =
        workflowStub.startUpdate(
            "update", WorkflowUpdateStage.ACCEPTED, String.class, 10_000, "some-value");

    // Complete workflow, since the update is accepted it will not block completion
    workflowStub.update("complete", void.class);
    assertEquals("complete", workflowStub.getResult(String.class));
  }

  @Test(timeout = 70_000)
  public void LongRunningWorkflowUpdateId() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = BlockingWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    WorkflowUpdateHandle<String> handle =
        workflowStub.startUpdate(
            "update", WorkflowUpdateStage.ACCEPTED, String.class, 65_000, "some-value");

    assertEquals("some-value", handle.getResultAsync().get());
    workflowStub.update("complete", void.class);
    assertEquals("complete", workflowStub.getResult(String.class));
  }

  @Test
  public void WorkflowUpdateGetResultAsyncTimeout() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = BlockingWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    WorkflowUpdateHandle<String> handle =
        workflowStub.startUpdate(
            "update", WorkflowUpdateStage.ACCEPTED, String.class, 10_000, "some-value");

    CompletableFuture<String> result = handle.getResultAsync(2, TimeUnit.SECONDS);
    // Verify get throws the correct exception in around the right amount of time
    Stopwatch stopWatch = Stopwatch.createStarted();
    ExecutionException executionException = assertThrows(ExecutionException.class, result::get);
    assertThat(
        executionException.getCause(),
        is(instanceOf(WorkflowUpdateTimeoutOrCancelledException.class)));
    stopWatch.stop();
    long elapsedSeconds = stopWatch.elapsed(TimeUnit.SECONDS);
    assertTrue(
        "We shouldn't return too early or too late by the timeout, took "
            + elapsedSeconds
            + " seconds",
        elapsedSeconds >= 1 && elapsedSeconds <= 3);

    // Complete workflow, since the update is accepted it will not block completion
    workflowStub.update("complete", void.class);
    assertEquals("complete", workflowStub.getResult(String.class));
  }

  @Test
  public void WorkflowUpdateGetResultTimeout() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = BlockingWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    WorkflowUpdateHandle<String> handle =
        workflowStub.startUpdate(
            "update", WorkflowUpdateStage.ACCEPTED, String.class, 10_000, "some-value");

    // Verify get throws the correct exception in around the right amount of time
    Stopwatch stopWatch = Stopwatch.createStarted();
    assertThrows(
        WorkflowUpdateTimeoutOrCancelledException.class,
        () -> handle.getResult(2, TimeUnit.SECONDS));
    stopWatch.stop();
    long elapsedSeconds = stopWatch.elapsed(TimeUnit.SECONDS);
    assertTrue(
        "We shouldn't return too early or too late by the timeout, took "
            + elapsedSeconds
            + " seconds",
        elapsedSeconds >= 1 && elapsedSeconds <= 3);

    // Complete workflow, since the update is accepted it will not block completion
    workflowStub.update("complete", void.class);
    assertEquals("complete", workflowStub.getResult(String.class));
  }

  @WorkflowInterface
  public interface BlockingWorkflow {
    @WorkflowMethod
    String execute();

    @QueryMethod
    String getState();

    @UpdateMethod(name = "update")
    String update(long sleep, String value);

    @UpdateMethod
    void complete();
  }

  public static class BlockingWorkflowImpl implements UpdateTestTimeout.BlockingWorkflow {
    CompletablePromise<Void> promise = Workflow.newPromise();
    CompletablePromise<Void> updateExecutePromise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "complete";
    }

    @Override
    public String getState() {
      return "running";
    }

    @Override
    public String update(long sleep, String value) {
      Workflow.sleep(sleep);
      return value;
    }

    @Override
    public void complete() {
      promise.complete(null);
    }
  }
}
