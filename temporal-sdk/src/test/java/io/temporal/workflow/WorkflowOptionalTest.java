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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowOptionalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(OptionalWorkflowImpl.class).build();

  @Test
  public void testOptionalArgumentsWorkflow() throws InterruptedException {
    int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
    TestWorkflows.OptionalWorkflow client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.OptionalWorkflow.class);
    WorkflowClient.start(client::execute);
    testWorkflowRule.sleep(Duration.ofSeconds(1));
    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      Optional<UUID> uuid = Optional.of(UUID.randomUUID());
      Assert.assertEquals("some state" + uuid, client.getState(uuid).get());
      if (testWorkflowRule.isUseExternalService()) {
        // Sleep a little bit to avoid server throttling error.
        Thread.sleep(50);
      }
    }
    client.mySignal(Optional.of("Hello "));
    Optional<String> result = WorkflowStub.fromTyped(client).getResult(Optional.class);
    assertEquals("done", result.get());
  }

  public static class OptionalWorkflowImpl implements TestWorkflows.OptionalWorkflow {

    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public Optional<String> execute() {
      promise.get();
      return Optional.of("done");
    }

    @Override
    public Optional<String> getState(Optional<UUID> uuid) {
      return Optional.of("some state" + uuid);
    }

    @Override
    public void mySignal(Optional<String> value) {
      promise.complete(null);
    }
  }
}
