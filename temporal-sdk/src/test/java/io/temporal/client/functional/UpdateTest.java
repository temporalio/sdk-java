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

package io.temporal.client.functional;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.Rule;
import org.junit.Test;

public class UpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              UpdateTest.QuickWorkflowWithUpdateImpl.class,
              UpdateTest.LongWorkflowWithUpdateImpl.class)
          .build();

  @Test
  public void updateNonExistentWorkflow() {
    TestWorkflows.TestUpdatedWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, "non-existing-id");
    assertThrows(WorkflowNotFoundException.class, () -> workflow.update("some-value"));
  }

  @Test
  public void pollUpdateNonExistentWorkflow() throws ExecutionException, InterruptedException {
    WorkflowStub workflowStub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("non-existing-id");
    // Getting the update handle to a nonexistent workflow is fine
    WorkflowUpdateHandle<String> handle = workflowStub.getUpdateHandle("update-id", String.class);
    assertThrows(Exception.class, () -> handle.getResultAsync().get());
  }

  @Test
  public void updateNonExistentWorkflowUntyped() {
    WorkflowStub workflowStub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("non-existing-id");
    assertThrows(
        WorkflowNotFoundException.class,
        () -> workflowStub.update("update", Void.class, "some-value"));

    assertThrows(
        WorkflowNotFoundException.class,
        () ->
            workflowStub.startUpdate(
                "update", WorkflowUpdateStage.ACCEPTED, Void.class, "some-value"));
  }

  @Test
  public void updateCompletedWorkflow() {
    TestWorkflows.TestUpdatedWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.TestUpdatedWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    workflow.execute();
    assertThrows(WorkflowNotFoundException.class, () -> workflow.update("some-value"));
  }

  @Test
  public void updateCompletedWorkflowUntyped() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.TestUpdatedWorkflow.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    WorkflowExecution execution = workflowStub.start();
    workflowStub.getResult(String.class);

    assertThrows(
        WorkflowNotFoundException.class,
        () -> workflowStub.update("update", Void.class, "some-value"));

    assertThrows(
        WorkflowNotFoundException.class,
        () ->
            workflowStub.startUpdate(
                "update", WorkflowUpdateStage.ACCEPTED, Void.class, "some-value"));
  }

  @Test
  public void updateWorkflowDuplicateId() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    WorkflowExecution execution = workflowStub.start();
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    String updateId = "update-id";

    // Try to get the result of an invalid update
    WorkflowUpdateHandle<String> handle = workflowStub.getUpdateHandle(updateId, String.class);
    assertThrows(Exception.class, () -> handle.getResultAsync().get());

    assertEquals(
        "some-value",
        workflowStub
            .startUpdate(
                UpdateOptions.newBuilder(String.class)
                    .setUpdateName("update")
                    .setUpdateId(updateId)
                    .setFirstExecutionRunId(execution.getRunId())
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                    .build(),
                0,
                "some-value")
            .getResultAsync()
            .get());
    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    // Try to send another update request with the same update Id
    assertEquals(
        "some-value",
        workflowStub
            .startUpdate(
                UpdateOptions.newBuilder(String.class)
                    .setUpdateName("update")
                    .setUpdateId(updateId)
                    .setFirstExecutionRunId(execution.getRunId())
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                    .build(),
                "some-other-value")
            .getResultAsync()
            .get());

    // Try to poll the update before the workflow is complete.
    assertEquals("some-value", handle.getResultAsync().get());
    // Complete the workflow
    workflowStub.update("complete", void.class);
    assertEquals("complete", workflowStub.getResult(String.class));

    // Try to poll again
    assertEquals("some-value", handle.getResultAsync().get());
  }

  @Test
  public void updateWithStart() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // first update-with-start
    WorkflowStartOperationUpdate<String> update1 =
        WorkflowStartOperationUpdate.newBuilder(
                "update", String.class, new Object[] {0, "Hello Update"})
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();
    WorkflowStub workflowStub1 =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                .toBuilder()
                .setWorkflowId(workflowId)
                .build());
    WorkflowExecution execution1 =
        workflowStub1.startWithOperation(update1, new String[] {"some-value"}, new String[] {});

    assertEquals(workflowId, execution1.getWorkflowId());
    assertEquals("Hello Update", update1.getResult());

    WorkflowUpdateHandle<String> updHandle1 = update1.getUpdateHandle().get();
    assertEquals(workflowId, updHandle1.getExecution().getWorkflowId());
    assertEquals(execution1.getRunId(), updHandle1.getExecution().getRunId());
    assertEquals("Hello Update", updHandle1.getResultAsync().get());

    // second update-with-start
    WorkflowStartOperationUpdate<String> update2 =
        WorkflowStartOperationUpdate.newBuilder(
                "update", String.class, new Object[] {0, "Hello Update 2"})
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();
    WorkflowStub workflowStub2 =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                .toBuilder()
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowId(workflowId)
                .build());
    WorkflowExecution execution2 =
        workflowStub2.startWithOperation(update2, new String[] {"some-value"}, new String[] {});

    assertEquals(execution1.getRunId(), execution2.getRunId());
    assertEquals("Hello Update 2", update2.getResult());

    WorkflowUpdateHandle<String> updHandle2 = update2.getUpdateHandle().get();
    assertEquals(workflowId, updHandle2.getExecution().getWorkflowId());
    assertEquals(execution2.getRunId(), updHandle2.getExecution().getRunId());
    assertEquals("Hello Update 2", updHandle2.getResultAsync().get());

    workflowStub2.update("complete", void.class);
    assertEquals("complete", workflowStub2.getResult(String.class));
  }

  @Test
  public void updateWithStartOperationSingleUse() throws ExecutionException, InterruptedException {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowStartOperationUpdate<String> update =
        WorkflowStartOperationUpdate.newBuilder(
                "update", String.class, new Object[] {0, "Hello Update"})
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            TestWorkflows.WorkflowWithUpdate.class.getSimpleName(),
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                .toBuilder()
                .setWorkflowId(workflowId)
                .build());

    workflowStub.startWithOperation(update, new String[] {"some-value"}, new String[] {});

    try {
      workflowStub.startWithOperation(update, new String[] {"some-value"}, new String[] {});
    } catch (IllegalStateException e) {
      assertEquals(e.getMessage(), "WorkflowStartOperationUpdate was already executed");
    }
  }

  public static class QuickWorkflowWithUpdateImpl implements TestWorkflows.TestUpdatedWorkflow {

    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void update(String arg) {}
  }

  public static class LongWorkflowWithUpdateImpl implements TestWorkflows.WorkflowWithUpdate {
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return "complete";
    }

    @Override
    public String getState() {
      return "running";
    }

    @Override
    public String update(Integer index, String value) {
      return value;
    }

    @Override
    public void updateValidator(Integer index, String value) {}

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }
}
