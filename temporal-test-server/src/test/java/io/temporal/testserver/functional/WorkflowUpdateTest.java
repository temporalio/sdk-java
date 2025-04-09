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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.update.v1.*;
import io.temporal.api.workflowservice.v1.PollWorkflowExecutionUpdateRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowExecutionUpdateResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse;
import io.temporal.client.*;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowUpdateTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(UpdateWorkflowImpl.class).build();

  @Test
  public void updateBadWorkflow() {
    // Assert that we can't update a non-existent workflow. Expect a NOT_FOUND error.
    WorkflowExecution badExec = WorkflowExecution.newBuilder().setWorkflowId("workflowId").build();
    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                updateWorkflow(
                    badExec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
                    TestWorkflows.UpdateType.BLOCK));
    // Server does not return a consistent error message here, so we can't assert on the message
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());
  }

  @Test
  public void pollUpdateBadWorkflow() {
    // Assert that we can't poll an update for a non-existent workflow. Expect a NOT_FOUND error.
    WorkflowExecution badExec = WorkflowExecution.newBuilder().setWorkflowId("workflowId").build();
    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                pollWorkflowUpdate(
                    badExec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED));
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());
  }

  @Test
  public void pollUpdateBadUpdate() {
    // Assert that we can't poll an update for a non-existent update ID. Expect a NOT_FOUND error.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                pollWorkflowUpdate(
                    exec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED));
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());
  }

  @Test
  public void updateAndPollCompletedWorkflow() {
    // Assert that we can't update or poll a new update request for a completed workflow. Expect a
    // NOT_FOUND
    // error.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);
    workflowStub.signal();
    workflowStub.execute();

    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                updateWorkflow(
                    exec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
                    TestWorkflows.UpdateType.BLOCK));
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());

    exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                pollWorkflowUpdate(
                    exec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED));
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());
  }

  @Test
  public void update() {
    // Assert that we can update a workflow and poll the update.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        updateResponse.getStage());

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        pollUpdateResponse.getStage());
  }

  @Test
  public void updateCompleteWorkflow() {
    // Assert that an update completed in the same WFT as the workflow is completed is reported.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    updateWorkflow(
        exec,
        "updateId",
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        TestWorkflows.UpdateType.FINISH_WORKFLOW);

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        pollUpdateResponse.getStage());
  }

  @Test
  public void updateAdmittedNotSupported() {
    // Assert that we can't send an update with wait stage ADMITTED. Expect a
    // PERMISSION_DENIED error.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                updateWorkflow(
                    exec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
                    TestWorkflows.UpdateType.BLOCK));
    Assert.assertEquals(Status.PERMISSION_DENIED.getCode(), exception.getStatus().getCode());

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        updateResponse.getStage());
    Assert.assertEquals(
        Outcome.newBuilder()
            .setSuccess(
                Payloads.newBuilder()
                    .addPayloads(DefaultDataConverter.newDefaultInstance().toPayload(null).get())
                    .build())
            .build(),
        updateResponse.getOutcome());

    PollWorkflowExecutionUpdateResponse response =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED);
    Assert.assertEquals(updateResponse.getOutcome(), response.getOutcome());
    Assert.assertEquals(updateResponse.getUpdateRef(), response.getUpdateRef());
    Assert.assertEquals(updateResponse.getStage(), response.getStage());
  }

  @Test
  public void duplicateUpdate() {
    // Assert that sending a duplicate update request returns the same response as the original
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse1 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);

    UpdateWorkflowExecutionResponse updateResponse2 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(updateResponse1, updateResponse2);
  }

  @Test
  public void duplicateRejectedUpdate() {
    // Assert that rejected updates are not stored and a new request can use the same update id.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse1 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.REJECT);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        updateResponse1.getStage());

    UpdateWorkflowExecutionResponse updateResponse2 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        updateResponse2.getStage());

    workflowStub.signal();
    workflowStub.execute();
    Assert.assertEquals(updateResponse1.getUpdateRef(), updateResponse2.getUpdateRef());
    Assert.assertNotEquals(updateResponse1.getOutcome(), updateResponse2.getOutcome());
  }

  @Test
  public void updateRejected() {
    // Assert that we can't poll for a rejected update. Expect a NOT_FOUND error.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.REJECT);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        updateResponse.getStage());

    StatusRuntimeException exception =
        Assert.assertThrows(
            StatusRuntimeException.class,
            () ->
                pollWorkflowUpdate(
                    exec,
                    "updateId",
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED));
    Assert.assertEquals(Status.NOT_FOUND.getCode(), exception.getStatus().getCode());
  }

  @Test
  public void updateWaitStage() {
    // Assert that wait for stage returns when the update has reached at least the specified stage.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.COMPLETE);
    // Current behaviour is not defined, server may return ACCEPTED or COMPLETED
    Assert.assertTrue(
        updateResponse
                .getStage()
                .equals(
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED)
            || updateResponse
                .getStage()
                .equals(
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED));

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        pollUpdateResponse.getStage());
  }

  @Test(timeout = 120000)
  public void updateNotAcceptedTimeout() {
    // Assert that if an update cannot be accepted it will be considered admitted.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(5, TimeUnit.SECONDS);

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
        updateResponse.getStage());

    updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
        updateResponse.getStage());

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
        pollUpdateResponse.getStage());

    pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
        pollUpdateResponse.getStage());

    pollUpdateResponse = pollWorkflowUpdate(exec, "updateId", null);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED,
        pollUpdateResponse.getStage());
  }

  @Test(timeout = 60000)
  public void updateWaitCompletedTimeout() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        updateResponse.getStage());

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        pollUpdateResponse.getStage());

    pollUpdateResponse = pollWorkflowUpdate(exec, "updateId", null);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        pollUpdateResponse.getStage());
  }

  @Test
  public void updateAndPollByWorkflowId() {
    // Assert that we can update and poll a workflow by its workflowId without specifying the runId
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    WorkflowExecution workflowExecution =
        WorkflowExecution.newBuilder().setWorkflowId(exec.getWorkflowId()).build();
    UpdateWorkflowExecutionResponse updateResponse =
        updateWorkflow(
            workflowExecution,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(updateResponse.getUpdateRef().getWorkflowExecution(), exec);

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            workflowExecution,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    assertPollResponseEqualsUpdateResponse(pollUpdateResponse, updateResponse);
  }

  @Test
  public void getCompletedUpdateOfCompletedWorkflow() {
    // Assert that we can get and poll a completed update from a completed workflow.

    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse updateResponse1 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.COMPLETE);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        updateResponse1.getStage());

    workflowStub.signal();
    workflowStub.execute();

    UpdateWorkflowExecutionResponse updateResponse2 =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(updateResponse1, updateResponse2);

    PollWorkflowExecutionUpdateResponse pollUpdateResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED);
    assertPollResponseEqualsUpdateResponse(pollUpdateResponse, updateResponse1);
  }

  @Test
  public void getIncompleteUpdateOfCompletedWorkflow() {
    // Assert that the server fails an incomplete update if the workflow is completed.
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    TestWorkflows.WorkflowWithUpdate workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution exec = WorkflowClient.start(workflowStub::execute);

    UpdateWorkflowExecutionResponse response =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
        response.getStage());

    workflowStub.signal();
    workflowStub.execute();

    response =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        response.getStage());
    assertUpdateOutcomeIsAcceptedUpdateCompletedWorkflow(response.getOutcome());

    response =
        updateWorkflow(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED,
            TestWorkflows.UpdateType.BLOCK);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        response.getStage());
    assertUpdateOutcomeIsAcceptedUpdateCompletedWorkflow(response.getOutcome());

    PollWorkflowExecutionUpdateResponse pollResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        pollResponse.getStage());
    assertUpdateOutcomeIsAcceptedUpdateCompletedWorkflow(pollResponse.getOutcome());

    pollResponse =
        pollWorkflowUpdate(
            exec,
            "updateId",
            UpdateWorkflowExecutionLifecycleStage
                .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED);
    Assert.assertEquals(
        UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED,
        pollResponse.getStage());
    assertUpdateOutcomeIsAcceptedUpdateCompletedWorkflow(pollResponse.getOutcome());
  }

  private void assertUpdateOutcomeIsAcceptedUpdateCompletedWorkflow(Outcome outcome) {
    Assert.assertEquals(
        "Workflow Update failed because the Workflow completed before the Update completed.",
        outcome.getFailure().getMessage());
    Assert.assertEquals(
        "AcceptedUpdateCompletedWorkflow",
        outcome.getFailure().getApplicationFailureInfo().getType());
  }

  private UpdateWorkflowExecutionResponse updateWorkflow(
      WorkflowExecution execution,
      String updateId,
      UpdateWorkflowExecutionLifecycleStage stage,
      TestWorkflows.UpdateType type) {
    UpdateWorkflowExecutionResponse response =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .updateWorkflowExecution(
                UpdateWorkflowExecutionRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .setWorkflowExecution(execution)
                    .setRequest(
                        Request.newBuilder()
                            .setInput(
                                Input.newBuilder()
                                    .setName("update")
                                    .setArgs(
                                        DefaultDataConverter.newDefaultInstance()
                                            .toPayloads(type)
                                            .get())
                                    .build())
                            .setMeta(Meta.newBuilder().setUpdateId(updateId).build()))
                    .setWaitPolicy(WaitPolicy.newBuilder().setLifecycleStage(stage).build())
                    .build());

    // There are some assertions that we can always make...
    Assert.assertEquals(updateId, response.getUpdateRef().getUpdateId());
    Assert.assertEquals(
        execution.getWorkflowId(), response.getUpdateRef().getWorkflowExecution().getWorkflowId());
    return response;
  }

  private PollWorkflowExecutionUpdateResponse pollWorkflowUpdate(
      WorkflowExecution execution, String updateId, UpdateWorkflowExecutionLifecycleStage stage) {
    WaitPolicy.Builder waitPolicy = WaitPolicy.newBuilder();
    if (stage != null) {
      waitPolicy.setLifecycleStage(stage);
    }
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .pollWorkflowExecutionUpdate(
            PollWorkflowExecutionUpdateRequest.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setWaitPolicy(waitPolicy.build())
                .setUpdateRef(
                    UpdateRef.newBuilder()
                        .setUpdateId(updateId)
                        .setWorkflowExecution(execution)
                        .build())
                .build());
  }

  private void assertPollResponseEqualsUpdateResponse(
      PollWorkflowExecutionUpdateResponse pollResponse,
      UpdateWorkflowExecutionResponse updateResponse) {
    Assert.assertEquals(pollResponse.getStage(), updateResponse.getStage());
    Assert.assertEquals(pollResponse.getOutcome(), updateResponse.getOutcome());
    Assert.assertEquals(pollResponse.getUpdateRef(), updateResponse.getUpdateRef());
  }

  public static class UpdateWorkflowImpl implements TestWorkflows.WorkflowWithUpdate {
    boolean unblock = false;

    @Override
    public void execute() {
      // wait forever to keep it in running state
      Workflow.await(() -> unblock);
    }

    @Override
    public void update(TestWorkflows.UpdateType type) {
      if (type == TestWorkflows.UpdateType.DELAYED_COMPLETE) {
        Workflow.sleep(Duration.ofSeconds(1));
      } else if (type == TestWorkflows.UpdateType.BLOCK) {
        Workflow.await(() -> false);
      } else if (type == TestWorkflows.UpdateType.FINISH_WORKFLOW) {
        unblock = true;
      }
    }

    @Override
    public void updateValidator(TestWorkflows.UpdateType type) {
      if (type == TestWorkflows.UpdateType.REJECT) {
        throw new IllegalArgumentException("REJECT");
      }
    }

    @Override
    public void signal() {
      unblock = true;
    }
  }
}
