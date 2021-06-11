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

import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WorkflowRetryTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowRetryImpl.class).build();

  @Test
  public void testWorkflowRetry() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(3)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setRetryOptions(workflowRetryOptions)
                    .build());
    long start = testWorkflowRule.getTestEnvironment().currentTimeMillis();
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertEquals(
          e.toString(),
          "message='simulated 3', type='test', nonRetryable=false",
          e.getCause().getMessage());
    } finally {
      long elapsed = testWorkflowRule.getTestEnvironment().currentTimeMillis() - start;
      Assert.assertTrue(
          String.valueOf(elapsed), elapsed >= 2000); // Ensure that retry delays the restart
    }
  }

  public static class TestWorkflowRetryImpl implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int attempt = Workflow.getInfo().getAttempt();
      assertEquals(count.get() + 1, attempt);
      throw ApplicationFailure.newFailure("simulated " + count.incrementAndGet(), "test");
    }
  }
}
