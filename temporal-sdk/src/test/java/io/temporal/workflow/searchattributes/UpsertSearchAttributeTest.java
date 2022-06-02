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

package io.temporal.workflow.searchattributes;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableMap;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttribute;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;

public class UpsertSearchAttributeTest {

  private static final String TEST_VALUE = "test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUpsertSearchAttributesImpl.class)
          .build();

  @Test
  public void testUpsertSearchAttributes() {
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setSearchAttributes(ImmutableMap.of("CustomTextField", "custom"))
            .build();
    TestWorkflowStringArg testWorkflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflowStringArg.class, workflowOptions);
    WorkflowExecution execution =
        WorkflowClient.start(testWorkflow::execute, testWorkflowRule.getTaskQueue());
    testWorkflow.execute(testWorkflowRule.getTaskQueue());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "upsertSearchAttributes",
            "executeActivity Activity",
            "activity Activity");
    testWorkflowRule.assertHistoryEvent(
        execution, EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES);
  }

  public static class TestUpsertSearchAttributesImpl implements TestWorkflowStringArg {

    private static final AtomicBoolean FAILED = new AtomicBoolean();

    @Override
    public void execute(String taskQueue) {
      Map<String, List<?>> oldAttributes = Workflow.getSearchAttributes();
      assertEquals(1, oldAttributes.size());

      Map<String, Object> objectMap = ImmutableMap.of("CustomKeywordField", TEST_VALUE);
      Workflow.upsertSearchAttributes(objectMap);
      assertEquals(TEST_VALUE, Workflow.getSearchAttribute("CustomKeywordField"));
      Map<String, List<?>> newAttributes = Workflow.getSearchAttributes();
      assertEquals(2, newAttributes.size());
      // triggering the end of the workflow task
      Workflow.sleep(100);

      objectMap = ImmutableMap.of("CustomKeywordField", SearchAttribute.UNSET_VALUE);
      Workflow.upsertSearchAttributes(objectMap);
      assertNull(Workflow.getSearchAttribute("CustomKeywordField"));
      newAttributes = Workflow.getSearchAttributes();
      assertEquals(1, newAttributes.size());
      // triggering the end of the workflow task
      Workflow.sleep(100);

      // two upserts in one WFT works fine
      Workflow.upsertSearchAttributes(oldAttributes);
      Workflow.upsertSearchAttributes(newAttributes);
      Workflow.sleep(100);
      assertEquals(newAttributes, Workflow.getSearchAttributes());

      // This helps with replaying the history one more time to check
      // against a possible NonDeterministicWorkflowError which could be caused by missing
      // UpsertWorkflowSearchAttributes event in history.
      if (FAILED.compareAndSet(false, true)) {
        throw new IllegalStateException("force replay");
      }
    }
  }
}
