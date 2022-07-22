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

package io.temporal.testserver.functional;

import static org.junit.Assert.assertTrue;

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * We check that the child workflow that we don't specify explicit timeouts for can be abandoned by
 * the parent workflow with a short timeout and successfully finish much later than the timeout of
 * the parent. Cover a bug in test server when test server was propagating a timeout from the parent
 * to the child if the child timeout is not explicitly specified.
 */
public class ChildLivesLongerThanParentTest {
  private static final Duration PARENT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration SLEEPING_BY = PARENT_TIMEOUT.plus(Duration.ofSeconds(2));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class, ChildWorkflowWithTimerImpl.class)
          .build();

  private static final Signal timerFired = new Signal();

  @Test
  public void testAbandonChild() throws InterruptedException {
    testWorkflowRule
        .getWorkflowClient()
        .newWorkflowStub(
            TestWorkflows.PrimitiveWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowRunTimeout(PARENT_TIMEOUT)
                .build())
        .execute();
    assertTrue(timerFired.waitForSignal(SLEEPING_BY.plus(Duration.ofSeconds(2))));
  }

  public static class TestWorkflowImpl implements TestWorkflows.PrimitiveWorkflow {
    @Override
    public void execute() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
              .build();
      TestWorkflows.PrimitiveChildWorkflow child =
          Workflow.newChildWorkflowStub(TestWorkflows.PrimitiveChildWorkflow.class, options);
      Async.procedure(child::execute);
      // wait for a start, but not wait for a completion
      Workflow.getWorkflowExecution(child).get();
    }
  }

  public static class ChildWorkflowWithTimerImpl implements TestWorkflows.PrimitiveChildWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(SLEEPING_BY);
      timerFired.signal();
    }
  }
}
