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

package io.temporal.client.functional;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Stopwatch;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;

public class GetResultsTimeoutTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .build();

  @Test
  public void testGetResults() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(workflow::execute);

    Stopwatch stopWatch = Stopwatch.createStarted();

    assertThrows(
        TimeoutException.class,
        () -> WorkflowStub.fromTyped(workflow).getResult(2, TimeUnit.SECONDS, Void.class));

    stopWatch.stop();
    long elapsedSeconds = stopWatch.elapsed(TimeUnit.SECONDS);
    assertTrue(
        "We shouldn't return too early or too late by the timeout, took "
            + elapsedSeconds
            + " seconds",
        elapsedSeconds >= 1 && elapsedSeconds <= 3);
  }

  @Test
  public void testGetResultAsync() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(workflow::execute);
    CompletableFuture<Void> future =
        WorkflowStub.fromTyped(workflow).getResultAsync(2, TimeUnit.SECONDS, Void.class);

    Stopwatch stopWatch = Stopwatch.createStarted();

    ExecutionException executionException = assertThrows(ExecutionException.class, future::get);
    assertThat(executionException.getCause(), is(instanceOf(TimeoutException.class)));

    stopWatch.stop();
    long elapsedSeconds = stopWatch.elapsed(TimeUnit.SECONDS);
    assertTrue(
        "We shouldn't return too early or too late by the timeout, took "
            + elapsedSeconds
            + " seconds",
        elapsedSeconds >= 1 && elapsedSeconds <= 3);
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofSeconds(10));
    }
  }
}
