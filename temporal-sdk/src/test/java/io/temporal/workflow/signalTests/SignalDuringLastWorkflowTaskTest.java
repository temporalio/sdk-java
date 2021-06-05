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

package io.temporal.workflow.signalTests;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalDuringLastWorkflowTaskTest {

  private static final AtomicInteger workflowTaskCount = new AtomicInteger();
  private static CompletableFuture<Boolean> sendSignal;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalDuringLastWorkflowTaskWorkflowImpl.class)
          .build();

  @Test
  public void testSignalDuringLastWorkflowTask() {
    WorkflowOptions options =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowId("testSignalDuringLastWorkflowTask-" + UUID.randomUUID().toString())
            .build();
    TestSignaledWorkflow client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestSignaledWorkflow.class, options);
    WorkflowExecution execution = WorkflowClient.start(client::execute);
    testWorkflowRule.registerDelayedCallback(
        Duration.ofSeconds(1),
        () -> {
          try {
            try {
              sendSignal.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            client.signal("Signal Input");
          } catch (TimeoutException | ExecutionException e) {
            throw new RuntimeException(e);
          }
          Assert.assertEquals(
              "Signal Input",
              testWorkflowRule
                  .getWorkflowClient()
                  .newUntypedWorkflowStub(execution, Optional.empty())
                  .getResult(String.class));
        });
    testWorkflowRule.sleep(Duration.ofSeconds(2));
  }

  static class TestSignalDuringLastWorkflowTaskWorkflowImpl implements TestSignaledWorkflow {

    private String signal;

    @Override
    public String execute() {
      if (workflowTaskCount.incrementAndGet() == 1) {
        sendSignal.complete(true);
        // Never sleep in a real workflow using Thread.sleep.
        // Here it is to simulate a race condition.
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      return signal;
    }

    @Override
    public void signal(String arg) {
      signal = arg;
    }
  }
}
