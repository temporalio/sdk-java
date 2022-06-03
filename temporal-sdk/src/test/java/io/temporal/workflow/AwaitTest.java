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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class AwaitTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestAwait.class).build();

  @Test
  public void testAwait() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(" awoken i=1 loop i=1 awoken i=2 loop i=2 awoken i=3", result);
  }

  public static class TestAwait implements TestWorkflow1 {

    private int i;
    private int j;

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      Async.procedure(
          () -> {
            while (true) {
              Workflow.await(() -> i > j);
              result.append(" awoken i=" + i);
              j++;
            }
          });

      for (i = 1; i < 3; i++) {
        Workflow.await(() -> j >= i);
        result.append(" loop i=" + i);
      }
      assertFalse(Workflow.await(Duration.ZERO, () -> false));
      return result.toString();
    }
  }
}
