/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow2;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class UpsertSearchAttributesTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUpsertSearchAttributesImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void testUpsertSearchAttributes() {
    TestWorkflow2 testWorkflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow2.class);
    WorkflowExecution execution =
        WorkflowClient.start(testWorkflow::execute, testWorkflowRule.getTaskQueue(), "testKey");
    String result = testWorkflow.execute(testWorkflowRule.getTaskQueue(), "testKey");
    assertEquals("done", result);
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

  public static class TestUpsertSearchAttributesImpl implements TestWorkflow2 {

    @Override
    public String execute(String taskQueue, String keyword) {
      Map<String, Object> searchAttributes = Workflow.getSearchAttributes();
      assertNull(searchAttributes);

      Map<String, Object> searchAttrMap = new HashMap<>();
      searchAttrMap.put("CustomKeywordField", keyword);
      Workflow.upsertSearchAttributes(searchAttrMap);

      searchAttributes = Workflow.getSearchAttributes();
      assertEquals("testKey", searchAttributes.get("CustomKeywordField"));

      // Running the activity below ensures that we have one more workflow task to be executed after
      // adding the search attributes. This helps with replaying the history one more time to check
      // against a possible NonDeterministicWorkflowError which could be caused by missing
      // UpsertWorkflowSearchAttributes event in history.
      VariousTestActivities activities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      activities.activity();

      return "done";
    }
  }
}
