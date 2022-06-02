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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivities;
import io.temporal.workflow.shared.TestActivities.CompletionClientActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityWithCompletionClientTest {
  private static final CompletionClientActivitiesImpl completionClientActivitiesImpl =
      new CompletionClientActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncActivityWorkflowImpl.class)
          .setActivityImplementations(completionClientActivitiesImpl)
          .build();

  @After
  public void tearDown() throws Exception {
    completionClientActivitiesImpl.close();
  }

  @Test
  public void testAsyncActivity() {
    completionClientActivitiesImpl.completionClient =
        testWorkflowRule.getWorkflowClient().newActivityCompletionClient();
    TestWorkflow1 client = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = client.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("workflow", result);
    Assert.assertEquals("activity1", completionClientActivitiesImpl.invocations.get(0));
  }

  public static class TestAsyncActivityWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {

      CompletionClientActivities completionClientActivities =
          Workflow.newActivityStub(
              CompletionClientActivities.class,
              SDKTestOptions.newActivityOptions20sScheduleToClose());

      Promise<String> a1 = Async.function(completionClientActivities::activity1, "1");
      assertEquals("1", a1.get());
      return "workflow";
    }
  }
}
