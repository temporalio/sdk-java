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

package io.temporal.workflow.versionTests;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionWithoutCommandEventTest {

  private static CompletableFuture<Boolean> executionStarted = new CompletableFuture<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestGetVersionWithoutCommandEventWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testGetVersionWithoutCommandEvent() throws Exception {
    executionStarted = new CompletableFuture<Boolean>();
    TestSignaledWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignaledWorkflow.class);
    WorkflowClient.start(workflowStub::execute);
    executionStarted.get();
    workflowStub.signal("test signal");
    String result = WorkflowStub.fromTyped(workflowStub).getResult(String.class);
    Assert.assertEquals("result 1", result);
  }

  public static class TestGetVersionWithoutCommandEventWorkflowImpl
      implements TestSignaledWorkflow {

    CompletablePromise<Boolean> signalReceived = Workflow.newPromise();

    @Override
    public String execute() {
      try {
        if (!Workflow.isReplaying()) {
          executionStarted.complete(true);
          signalReceived.get();
        } else {
          // Execute getVersion in replay mode. In this case we have no command event, only a
          // signal.
          int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
          if (version == Workflow.DEFAULT_VERSION) {
            signalReceived.get();
            return "result 1";
          } else {
            return "result 2";
          }
        }
        Workflow.sleep(1000);
      } catch (Exception e) {
        throw new RuntimeException("failed to get from signal");
      }

      throw new RuntimeException("unreachable");
    }

    @Override
    public void signal(String arg) {
      signalReceived.complete(true);
    }
  }
}
