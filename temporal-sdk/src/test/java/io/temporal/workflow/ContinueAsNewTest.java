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

import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest {

  public static final int INITIAL_COUNT = 4;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestContinueAsNewImpl.class).build();

  @Test
  public void testContinueAsNew() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    options =
        WorkflowOptions.newBuilder(options)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build())
            .build();
    TestContinueAsNew client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestContinueAsNew.class, options);
    int result = client.execute(INITIAL_COUNT, testWorkflowRule.getTaskQueue());
    Assert.assertEquals(111, result);
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "continueAsNew",
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method");
  }

  @WorkflowInterface
  public interface TestContinueAsNew {

    @WorkflowMethod
    int execute(int count, String continueAsNewTaskQueue);
  }

  public static class TestContinueAsNewImpl implements TestContinueAsNew {

    @Override
    public int execute(int count, String continueAsNewTaskQueue) {
      String taskQueue = Workflow.getInfo().getTaskQueue();
      if (count >= INITIAL_COUNT - 2) {
        assertEquals(10, Workflow.getInfo().getRetryOptions().getMaximumAttempts());
      } else {
        assertEquals(5, Workflow.getInfo().getRetryOptions().getMaximumAttempts());
      }
      if (count == 0) {
        assertEquals(continueAsNewTaskQueue, taskQueue);
        return 111;
      }
      Map<String, Object> memo = new HashMap<>();
      memo.put("myKey", "MyValue");
      RetryOptions retryOptions = null;
      // don't specify retryOptions on the first continue-as-new to test that they are copied from
      // the previous run.
      if (count < INITIAL_COUNT - 1) {
        retryOptions = RetryOptions.newBuilder().setMaximumAttempts(5).build();
      }
      SearchAttributes searchAttributes =
          SearchAttributes.newBuilder()
              .set(SearchAttributeKey.forKeyword("CustomKeywordField"), "foo1")
              .build();
      ContinueAsNewOptions options =
          ContinueAsNewOptions.newBuilder()
              .setTaskQueue(continueAsNewTaskQueue)
              .setRetryOptions(retryOptions)
              .setMemo(memo)
              .setTypedSearchAttributes(searchAttributes)
              .build();
      TestContinueAsNew next = Workflow.newContinueAsNewStub(TestContinueAsNew.class, options);
      next.execute(count - 1, continueAsNewTaskQueue);
      throw new RuntimeException("unreachable");
    }
  }
}
