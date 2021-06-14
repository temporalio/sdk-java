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

import io.temporal.activity.ActivityInterface;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow3;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LargeHistoryTest {

  private static final Logger log = LoggerFactory.getLogger(LargeHistoryTest.class);
  private final TestLargeWorkflowActivityImpl activitiesImpl = new TestLargeWorkflowActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLargeHistory.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  @Ignore // Requires DEBUG_TIMEOUTS=true
  public void testLargeHistory() {
    final int activityCount = 1000;
    TestWorkflow3 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow3.class,
                TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setWorkflowTaskTimeout(Duration.ofSeconds(30))
                    .build());
    long start = System.currentTimeMillis();
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), activityCount);
    long duration = System.currentTimeMillis() - start;
    log.info(testWorkflowRule.getTestEnvironment().getNamespace() + " duration is " + duration);
    Assert.assertEquals("done", result);
  }

  @ActivityInterface
  public interface TestLargeWorkflowActivity {
    String activity();
  }

  public static class TestLargeWorkflowActivityImpl implements TestLargeWorkflowActivity {
    @Override
    public String activity() {
      return "done";
    }
  }

  public static class TestLargeHistory implements TestWorkflow3 {

    @Override
    public String execute(String taskQueue, int activityCount) {
      TestLargeWorkflowActivity activities =
          Workflow.newActivityStub(
              TestLargeWorkflowActivity.class,
              TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      List<Promise<String>> results = new ArrayList<>();
      for (int i = 0; i < activityCount; i++) {
        Promise<String> result = Async.function(activities::activity);
        results.add(result);
      }
      Promise.allOf(results).get();
      return "done";
    }
  }
}
