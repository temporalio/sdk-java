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

import com.google.common.base.Throwables;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestChild;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow3;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ChildWorkflowTimeoutTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestParentWorkflowWithChildTimeout.class, TestChild.class)
          .build();

  @Test
  public void testChildWorkflowTimeout() {
    TestWorkflow1 client = testWorkflowRule.newWorkflowStub200sTimeoutOptions(TestWorkflow1.class);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    assertTrue(result, result.contains("ChildWorkflowFailure"));
    assertTrue(result, result.contains("TimeoutFailure"));
  }

  public static class TestParentWorkflowWithChildTimeout implements TestWorkflow1 {

    private final TestWorkflow3 child;

    public TestParentWorkflowWithChildTimeout() {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofSeconds(1)).build();
      child = Workflow.newChildWorkflowStub(TestWorkflow3.class, options);
    }

    @Override
    public String execute(String taskQueue) {
      try {
        child.execute("Hello ", (int) Duration.ofDays(1).toMillis());
      } catch (Exception e) {
        return Throwables.getStackTraceAsString(e);
      }
      throw new RuntimeException("not reachable");
    }
  }
}
