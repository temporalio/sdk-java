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

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.replay.InternalWorkflowTaskException;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestWorkflows.DeterminismFailingWorkflowImpl;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NonDeterministicWorkflowPolicyFailWorkflowTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new TestActivitiesImpl())
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(Throwable.class)
                  .build(),
              DeterminismFailingWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Test
  public void testNonDeterministicWorkflowPolicyFailWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();
    TestWorkflowStringArg workflowStub =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestWorkflowStringArg.class, options);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("unreachable");
    } catch (WorkflowFailedException e) {
      // expected to fail on non deterministic error
      Assert.assertTrue(e.getCause() instanceof ApplicationFailure);
      Assert.assertEquals(
          InternalWorkflowTaskException.class.getName(),
          ((ApplicationFailure) e.getCause()).getType());
    }
  }
}
