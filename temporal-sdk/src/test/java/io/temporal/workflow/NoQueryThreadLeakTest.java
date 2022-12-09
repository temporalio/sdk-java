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

package io.temporal.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.QueryableWorkflow;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NoQueryThreadLeakTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestNoQueryWorkflowImpl.class).build();

  @Test
  public void testNoQueryThreadLeak() throws InterruptedException {
    QueryableWorkflow client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(QueryableWorkflow.class);
    WorkflowClient.start(client::execute);
    testWorkflowRule.sleep(Duration.ofSeconds(1));
    int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

    // Calls query multiple times to check at the end of the method that if it doesn't leak threads
    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      Assert.assertEquals("some state", client.getState());
      if (testWorkflowRule.isUseExternalService()) {
        // Sleep a little bit to avoid server throttling error.
        Thread.sleep(50);
      }
    }
    client.mySignal("Hello ");
    WorkflowStub.fromTyped(client).getResult(String.class);
    // Ensures that no threads were leaked due to query
    int threadsCreated = ManagementFactory.getThreadMXBean().getThreadCount() - threadCount;
    Assert.assertTrue("query leaks threads: " + threadsCreated, threadsCreated < queryCount);
  }

  public static class TestNoQueryWorkflowImpl implements QueryableWorkflow {

    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "done";
    }

    @Override
    public String getState() {
      return "some state";
    }

    @Override
    public void mySignal(String value) {
      promise.complete(null);
    }
  }
}
