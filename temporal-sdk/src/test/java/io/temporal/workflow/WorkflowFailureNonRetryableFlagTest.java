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

public class WorkflowFailureNonRetryableFlagTest {

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowNonRetryableFlag.class).build();

  @Test
  public void testWorkflowFailureNonRetryableFlag() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(100)
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
    try {
      workflowStub.execute(testName.getMethodName());
      Assert.fail("unreachable");
    } catch (WorkflowException e) {
      Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
      Assert.assertEquals("foo", ((ApplicationFailure) e.getCause()).getType());
      Assert.assertEquals(
          "details1", ((ApplicationFailure) e.getCause()).getDetails().get(0, String.class));
      Assert.assertEquals(
          Integer.valueOf(123),
          ((ApplicationFailure) e.getCause()).getDetails().get(1, Integer.class));
      Assert.assertEquals(
          "message='simulated 3', type='foo', nonRetryable=true", e.getCause().getMessage());
    }
  }

  public static class TestWorkflowNonRetryableFlag implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.get(testName);
      if (count == null) {
        count = new AtomicInteger();
        retryCount.put(testName, count);
      }
      int c = count.incrementAndGet();
      ApplicationFailure f =
          ApplicationFailure.newFailure("simulated " + c, "foo", "details1", 123);
      if (c == 3) {
        f.setNonRetryable(true);
      }
      throw f;
    }
  }
}
