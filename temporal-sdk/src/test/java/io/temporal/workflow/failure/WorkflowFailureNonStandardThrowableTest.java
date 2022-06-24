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

package io.temporal.workflow.failure;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Test the behavior when workflow code throws a non-temporal exception and this exception is
 * specified in failWorkflowExceptionTypes <a
 * href="https://github.com/temporalio/sdk-java/issues/744">Issue #744</a>
 */
public class WorkflowFailureNonStandardThrowableTest {

  public static class NonStandardThrowable extends Throwable {}

  private static final Map<String, AtomicInteger> retryCount = new ConcurrentHashMap<>();

  @Rule public TestName testName = new TestName();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(NonStandardThrowable.class)
                  .build(),
              TestWorkflowNonStandardThrowable.class)
          .build();

  @Test
  public void nonStandardThrowable() {
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    try {
      workflowStub.execute(testName.getMethodName());
      fail();
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ApplicationFailure);
      ApplicationFailure applicationFailure = (ApplicationFailure) e.getCause();
      assertEquals(NonStandardThrowable.class.getName(), applicationFailure.getType());
    }
  }

  @Test
  public void nonStandardThrowableSuccessOnSecondAttempt() {
    RetryOptions workflowRetryOptions =
        RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMillis(1))
            .setMaximumAttempts(2)
            .setBackoffCoefficient(1.0)
            .build();
    TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow1.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setRetryOptions(workflowRetryOptions)
                    .build());

    String result = workflowStub.execute(testName.getMethodName());
    assertEquals("success", result);
    assertEquals(
        "Success is expected on a second run only",
        2,
        retryCount.get(testName.getMethodName()).get());
  }

  public static class TestWorkflowNonStandardThrowable implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      AtomicInteger count = retryCount.computeIfAbsent(testName, ignore -> new AtomicInteger());
      int c = count.incrementAndGet();
      if (c <= 1) {
        rethrow(new NonStandardThrowable());
        // unreachable
        return "fail";
      } else {
        return "success";
      }
    }
  }

  private static <T extends Throwable> void rethrow(Throwable e) throws T {
    if (e instanceof RuntimeException) {
      throw (RuntimeException) e;
    } else {
      @SuppressWarnings("unchecked")
      T toRethrow = (T) e;
      throw toRethrow;
    }
  }
}
