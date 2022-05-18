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

package io.temporal.client.functional;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * These tests are covering the situation when a getResult wait time crosses a boundary of when
 * server cuts the long poll and returns an empty response to a history long poll. It's 20 seconds.
 *
 * <p>It has a sync version in {@link GetResultsAsyncOverMaximumLongPollWaitTest} that is split to
 * reduce the total execution time
 */
public class GetResultsSyncOverMaximumLongPollWaitTest {
  private static final int HISTORY_LONG_POLL_TIMEOUT_SECONDS = 20;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseTimeskipping(false)
          .setWorkflowTypes(TestWorkflowImpl.class)
          .build();

  @Test(timeout = 2 * HISTORY_LONG_POLL_TIMEOUT_SECONDS * 1000)
  public void testGetResults() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(workflow::execute);
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(Duration.ofSeconds(3 * HISTORY_LONG_POLL_TIMEOUT_SECONDS / 2));
    }
  }
}
