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
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.*;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import static org.junit.Assert.assertEquals;

/**
 * This test demonstrates that if a parent calls continue as new NOT_FOUND as test server doesn't
 * enforce atomicity of continue-as-new.
 *
 * <p>This is reported as https://github.com/temporalio/sdk-java/issues/1538
 */
public class ContinueAsNewTimeSkippingTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ParentThatContinuesAsNew.class, SignalingChild.class)
          .build();

  @Test(timeout = 3000)
  public void test() {
    ListenerParent parent = testWorkflowRule.newWorkflowStub(ListenerParent.class);
    int count = parent.execute(0);
    assertEquals(2, count);
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
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
              .build();
      TestWorkflows.PrimitiveChildWorkflow child =
          Workflow.newChildWorkflowStub(TestWorkflows.PrimitiveChildWorkflow.class, options);
      Async.procedure(child::execute);
      // wait for a start, but not wait for a completion
      Workflow.getWorkflowExecution(child).get();
      Workflow.await(() -> this.count - count > 0 || this.count == 2);
      if (this.count == 2) {
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
      ListenerParent parent =
          Workflow.newExternalWorkflowStub(
              ListenerParent.class, Workflow.getInfo().getParentWorkflowId().get());
      Workflow.sleep(5000);
      parent.signal();
      log.info("signal sent");
    }
  }
}
