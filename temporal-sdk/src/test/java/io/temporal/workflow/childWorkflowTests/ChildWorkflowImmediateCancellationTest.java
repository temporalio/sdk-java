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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testUtils.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test ensures that the behavior of immediate (inside the same WFT as scheduling) child
 * workflow cancellation by the parent workflow.
 *
 * @see <a href="https://github.com/temporalio/sdk-java/issues/1037">Issue #1037</a>
 */
public class ChildWorkflowImmediateCancellationTest {

  private static final Signal CHILD_EXECUTED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowImpl.class, TestChildWorkflowImpl.class)
          .build();

  @Test
  public void testChildWorkflowImmediatelyCancelledByParent() throws InterruptedException {
    TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflowReturnString.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  public static class TestParentWorkflowImpl implements TestWorkflowReturnString {
    @Override
    public String execute() {
      NoArgsWorkflow child = Workflow.newChildWorkflowStub(NoArgsWorkflow.class);
      AtomicReference<Promise<Void>> childPromise = new AtomicReference<>();
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> childPromise.set(Async.procedure(child::execute)));
      cancellationScope.run();
      cancellationScope.cancel();
      cancellationScope.getCancellationRequest().get();
      ChildWorkflowFailure childWorkflowFailure =
          assertThrows(ChildWorkflowFailure.class, () -> childPromise.get().get());
      Throwable cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof CanceledFailure);
      return "ok";
    }
  }

  public static class TestChildWorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      CHILD_EXECUTED.signal();
      Workflow.sleep(Duration.ofHours(1));
    }
  }
}
