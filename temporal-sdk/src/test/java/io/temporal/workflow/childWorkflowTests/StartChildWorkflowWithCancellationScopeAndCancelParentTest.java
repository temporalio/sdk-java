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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class StartChildWorkflowWithCancellationScopeAndCancelParentTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ParentThatStartsChildInCancellationScope.class, SleepyChild.class)
          .build();

  @Test
  public void testStartChildWorkflowWithCancellationScopeAndCancelParent() {
    WorkflowStub workflow =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("TestWorkflowCancellationType");
    workflow.start(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED);
    workflow.cancel();
    try {
      workflow.getResult(Void.class);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }
  }

  public static class ParentThatStartsChildInCancellationScope
      implements TestWorkflows.TestWorkflowCancellationType {
    @Override
    public void execute(ChildWorkflowCancellationType cancellationType) {
      NoArgsWorkflow child =
          Workflow.newChildWorkflowStub(
              NoArgsWorkflow.class,
              ChildWorkflowOptions.newBuilder().setCancellationType(cancellationType).build());
      List<Promise<Void>> children = new ArrayList<>();
      // This is a non-blocking call that returns immediately.
      // Use child.composeGreeting("Hello", name) to call synchronously.
      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> {
                Promise<Void> promise = Async.procedure(child::execute);
                children.add(promise);
              });
      scope.run();
      Promise.allOf(children).get();
    }
  }

  public static class SleepyChild implements NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.await(() -> false);
    }
  }
}
