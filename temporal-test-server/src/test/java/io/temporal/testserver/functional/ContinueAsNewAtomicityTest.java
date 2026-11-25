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

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.*;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This test demonstrates that a child that signals a parent that calls continue-as-new
 * can receive NOT_FOUND as test server doesn't enforce atomicity of continue-as-new.
 *
 * This is reported as https://github.com/temporalio/sdk-java/issues/1538
 */
public class ContinueAsNewAtomicityTest {
  private static final int SIGNAL_COUNT = 500;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ParentThatContinuesAsNew.class, SignalingChild.class)
          .build();

  @Test(timeout = 10000)
  public void test() {
    ListenerParent parent = testWorkflowRule.newWorkflowStub(ListenerParent.class);
    int count = parent.execute(0);
    assertEquals(SIGNAL_COUNT, count);
  }

  @WorkflowInterface
  public interface ListenerParent {
    @WorkflowMethod
    /**
     * @return number of received signals
     */
    int execute(int count);

    @SignalMethod
    void signal();
  }

  public static class ParentThatContinuesAsNew implements ListenerParent {

    private final Logger log = Workflow.getLogger(ParentThatContinuesAsNew.class);

    private final ListenerParent nextRun = Workflow.newContinueAsNewStub(ListenerParent.class);

    private int count;

    @Override
    public int execute(int count) {
      this.count += count; // signal can be called before execute.
      // Only first run starts the child.
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
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
      Workflow.await(() -> this.count - count >= 5 || this.count == SIGNAL_COUNT);
      if (this.count == SIGNAL_COUNT) {
        return this.count;
      }
      log.info("continue-as-new");
      return nextRun.execute(this.count);
    }

    @Override
    public void signal() {
      log.info("signaled");
      this.count++;
    }
  }

  public static class SignalingChild implements TestWorkflows.PrimitiveChildWorkflow {

    private static final Logger log = Workflow.getLogger(ParentThatContinuesAsNew.class);

    @Override
    public void execute() {
      for (int i = 0; i < SIGNAL_COUNT; i++) {
        ListenerParent parent =
            Workflow.newExternalWorkflowStub(
                ListenerParent.class, Workflow.getInfo().getParentWorkflowId().get());
        parent.signal();
        log.info("signal sent");
        if (i % 20 == 0) {
          Workflow.sleep(1000);
        }
      }
    }
  }
}
