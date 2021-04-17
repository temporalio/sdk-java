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

import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class TimerTwoConcurrentFiresTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestTimerTwoConcurrentFiresWorkflowImpl.class)
          .build();

  @Test
  public void testTimerTwoConcurrentFires() {
    TestWorkflows.TestWorkflow1 client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    client.execute(testWorkflowRule.getTaskQueue());
  }

  public static class TestTimerTwoConcurrentFiresWorkflowImpl
      implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      Workflow.newTimer(Duration.ofMinutes(1));
      Workflow.newTimer(Duration.ofMinutes(1));

      Workflow.sleep(Duration.ofMinutes(10));

      return "";
    }
  }
}
