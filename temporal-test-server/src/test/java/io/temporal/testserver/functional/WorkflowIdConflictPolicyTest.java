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

package io.temporal.testserver.functional;

import static java.util.UUID.randomUUID;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.OnConflictOptions;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.client.*;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdConflictPolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SignalWorkflowImpl.class).build();

  @Test
  public void conflictPolicyUseExisting() {
    String workflowId = "conflict-policy-use-existing";
    String requestId = randomUUID().toString();

    // Start workflow
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRequestId(requestId)
            .build();
    TestWorkflows.WorkflowWithSignal workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithSignal.class, options);
    WorkflowExecution we = WorkflowClient.start(workflowStub::execute);

    StartWorkflowExecutionRequest request1 =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowId(workflowId)
            .setWorkflowType(WorkflowType.newBuilder().setName("WorkflowWithSignal"))
            .setTaskQueue(TaskQueue.newBuilder().setName(testWorkflowRule.getTaskQueue()))
            .setRequestId(requestId)
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build();

    // Same request ID should return same response
    StartWorkflowExecutionResponse response1 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request1);

    Assert.assertTrue(response1.getStarted());
    Assert.assertEquals(we.getRunId(), response1.getRunId());

    // Different request ID should still work but update history
    String newRequestId = randomUUID().toString();
    StartWorkflowExecutionRequest request2 =
        request1.toBuilder()
            .setRequestId(newRequestId)
            .setOnConflictOptions(OnConflictOptions.newBuilder().setAttachRequestId(true))
            .build();

    StartWorkflowExecutionResponse response2 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request2);

    Assert.assertFalse(response2.getStarted());
    Assert.assertEquals(we.getRunId(), response2.getRunId());

    // Same request ID should be deduped
    StartWorkflowExecutionResponse response3 =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .startWorkflowExecution(request2);

    Assert.assertFalse(response3.getStarted());
    Assert.assertEquals(we.getRunId(), response3.getRunId());

    // Since the WorkflowExecutionOptionsUpdatedEvent is buffered, it won't show
    // up at this point because there a workflow task running. So, I'm signaling
    // the workflow so it will complete.
    workflowStub.signal();
    WorkflowStub.fromTyped(workflowStub).getResult(Void.class);

    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(workflowId);
    List<HistoryEvent> events =
        history.getEvents().stream()
            .filter(
                item ->
                    item.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
            .collect(Collectors.toList());
    Assert.assertEquals(1, events.size());
    Assert.assertEquals(
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED, events.get(0).getEventType());
  }

  @Test
  public void conflictPolicyFail() {
    String workflowId = "conflict-policy-fail";
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflows.WorkflowWithSignal workflowStub1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithSignal.class, options);
    WorkflowClient.start(workflowStub1::execute);

    // Same workflow ID with conflict policy FAIL
    StartWorkflowExecutionRequest request1 =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setWorkflowId(workflowId)
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
            .build();

    StatusRuntimeException e =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowClient()
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .startWorkflowExecution(request1));
    Assert.assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());

    // Setting OnConflictOptions should result in failure as well
    StartWorkflowExecutionRequest request2 =
        request1.toBuilder()
            .setOnConflictOptions(OnConflictOptions.newBuilder().setAttachRequestId(true).build())
            .build();

    // Should throw since OnConflictOptions only valid with USE_EXISTING
    e =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowClient()
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .startWorkflowExecution(request2));

    Assert.assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());
  }

  public static class SignalWorkflowImpl implements TestWorkflows.WorkflowWithSignal {
    boolean unblock = false;

    @Override
    public void execute() {
      Workflow.await(() -> unblock);
    }

    @Override
    public void signal() {
      unblock = true;
    }
  }
}
