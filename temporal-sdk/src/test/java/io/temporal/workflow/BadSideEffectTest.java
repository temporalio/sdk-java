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

import static org.junit.Assert.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class BadSideEffectTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(Error.class)
                  .build(),
              TestSideEffectWorkflowImpl.class)
          .build();

  @Test
  public void testBadSideEffect() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    for (String testCase : TestWorkflows.illegalCallCases) {
      assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(testCase));
    }
  }

  public static class TestSideEffectWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String testCase) {
      Workflow.sideEffect(
          void.class,
          () -> {
            TestWorkflows.illegalCalls(testCase);
            return null;
          });
      return "";
    }
  }
}
