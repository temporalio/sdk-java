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

package io.temporal.workflow.versionTests;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test provides a localized reproduction for a state machine issue
 * https://github.com/temporalio/sdk-java/issues/615 This test has a corresponding state machine
 * unit test {@link
 * io.temporal.internal.statemachines.VersionStateMachineTest#testRecordAfterCommandCancellation}
 */
public class GetVersionAfterScopeCancellationTest {

  public static final class ReminderWorkflowImpl implements ReminderWorkflow {

    @Override
    public void start() {
      Workflow.sleep(Duration.ofDays(1));
    }

    @Override
    public void signal() {
      CancellationScope activeScope1 =
          Workflow.newCancellationScope(() -> Workflow.newTimer(Duration.ofHours(4)));
      activeScope1.run();

      Workflow.getVersion("some-change", Workflow.DEFAULT_VERSION, 1);

      activeScope1.cancel();

      // it's critical for this duration to be short for the test to fail originally (4s or less)
      Duration secondScopeTimerDuration = Duration.ofSeconds(4);
      Workflow.newTimer(secondScopeTimerDuration);
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ReminderWorkflowImpl.class)
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .build();

  @Test
  public void testGetVersionAndCancelTimer() {
    ReminderWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ReminderWorkflow.class);

    WorkflowClient.start(workflowStub::start);
    workflowStub.signal();

    WorkflowStub untypedWorkflowStub = WorkflowStub.fromTyped(workflowStub);
    untypedWorkflowStub.getResult(Void.TYPE);

    testWorkflowRule.assertNoHistoryEvent(
        untypedWorkflowStub.getExecution(), EVENT_TYPE_WORKFLOW_TASK_FAILED);
  }

  @WorkflowInterface
  public interface ReminderWorkflow {

    @WorkflowMethod
    void start();

    @SignalMethod
    void signal();
  }
}
