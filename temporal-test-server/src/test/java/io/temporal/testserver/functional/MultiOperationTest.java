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

import static org.junit.Assert.*;

import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.TaskQueueKind;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.errordetails.v1.MultiOperationExecutionFailure;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.update.v1.WaitPolicy;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;

public class MultiOperationTest {
  private static final String WORKFLOW_ID = "test-workflow-id";
  private static final String WORKFLOW_TYPE = "WorkflowWithUpdate";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(UpdateWorkflowImpl.class).build();

  @Test
  public void startAndUpdate() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId(WORKFLOW_ID)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<Void> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(
                workflow::update, TestWorkflows.UpdateType.COMPLETE)
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();
    WorkflowUpdateHandle<Void> updHandle =
        WorkflowClient.updateWithStart(workflow::execute, updateOp);
    assertNull(updHandle.getResultAsync().get());
  }

  @Test
  public void failWhenStartOperationIsInvalid() {
    // general start workflow validation
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) -> {
                      TaskQueue.Builder invalidTaskQueue = TaskQueue.newBuilder();
                      builder
                          .addOperations(
                              ExecuteMultiOperationRequest.Operation.newBuilder()
                                  .setStartWorkflow(
                                      validStartRequest().setTaskQueue(invalidTaskQueue))
                                  .build())
                          .addOperations(
                              ExecuteMultiOperationRequest.Operation.newBuilder()
                                  .setUpdateWorkflow(validUpdateRequest().build()));
                    }));
    MultiOperationExecutionFailure failure =
        StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals("INVALID_ARGUMENT: Missing TaskQueue.", failure.getStatuses(0).getMessage());
    assertEquals("Operation was aborted", failure.getStatuses(1).getMessage());

    // unique to MultiOperation: invalid CronSchedule option
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        validStartRequest().setCronSchedule("0 */12 * * *")))
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(validUpdateRequest()))));
    failure = StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals(
        "INVALID_ARGUMENT: CronSchedule is not allowed.", failure.getStatuses(0).getMessage());
    assertEquals("Operation was aborted", failure.getStatuses(1).getMessage());

    // unique to MultiOperation: invalid RequestEagerExecution option
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        validStartRequest().setRequestEagerExecution(true)))
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(validUpdateRequest()))));
    failure = StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals(
        "INVALID_ARGUMENT: RequestEagerExecution is not supported.",
        failure.getStatuses(0).getMessage());
    assertEquals("Operation was aborted", failure.getStatuses(1).getMessage());

    // unique to MultiOperation: invalid WorkflowStartDelay option
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        validStartRequest()
                                            .setWorkflowStartDelay(
                                                com.google.protobuf.Duration.newBuilder()
                                                    .setSeconds(1))))
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(validUpdateRequest()))));
    failure = StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals(
        "INVALID_ARGUMENT: WorkflowStartDelay is not supported.",
        failure.getStatuses(0).getMessage());
    assertEquals("Operation was aborted", failure.getStatuses(1).getMessage());
  }

  @Test
  public void failWhenUpdateOperationIsInvalid() {
    // general update workflow validation
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () -> {
              WaitPolicy.Builder invalidWaitPolicy = WaitPolicy.newBuilder();
              executeMultiOperation(
                  (builder) ->
                      builder
                          .addOperations(
                              ExecuteMultiOperationRequest.Operation.newBuilder()
                                  .setStartWorkflow(validStartRequest()))
                          .addOperations(
                              ExecuteMultiOperationRequest.Operation.newBuilder()
                                  .setUpdateWorkflow(
                                      validUpdateRequest().setWaitPolicy(invalidWaitPolicy))));
            });
    MultiOperationExecutionFailure failure =
        StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals("Operation was aborted", failure.getStatuses(0).getMessage());
    assertEquals(
        "INVALID_ARGUMENT: LifeCycle stage is required", failure.getStatuses(1).getMessage());

    // unique to MultiOperation: invalid RunId option
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(validStartRequest()))
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(
                                        validUpdateRequest()
                                            .setWorkflowExecution(
                                                WorkflowExecution.newBuilder()
                                                    .setWorkflowId(WORKFLOW_ID)
                                                    .setRunId("RUN_ID"))))));
    failure = StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals("Operation was aborted", failure.getStatuses(0).getMessage());
    assertEquals("INVALID_ARGUMENT: RunId is not allowed.", failure.getStatuses(1).getMessage());

    // unique to MultiOperation: invalid FirstExecutionRunId option
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(validStartRequest()))
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(
                                        validUpdateRequest().setFirstExecutionRunId("RUN_ID")))));
    failure = StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals("Operation was aborted", failure.getStatuses(0).getMessage());
    assertEquals(
        "INVALID_ARGUMENT: FirstExecutionRunId is not allowed.",
        failure.getStatuses(1).getMessage());
  }

  @Test
  public void failWhenMultiOperationWorkflowIDsNotMatching() {
    StatusRuntimeException exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        StartWorkflowExecutionRequest.newBuilder()
                                            .setWorkflowId("A")
                                            .build())
                                    .build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(
                                        UpdateWorkflowExecutionRequest.newBuilder()
                                            .setWorkflowExecution(
                                                WorkflowExecution.newBuilder().setWorkflowId("Z"))
                                            .build())
                                    .build())));
    MultiOperationExecutionFailure failure =
        StatusUtils.getFailure(exception, MultiOperationExecutionFailure.class);
    assertEquals(2, failure.getStatusesCount());
    assertEquals("Operation was aborted", failure.getStatuses(0).getMessage());
    assertEquals(
        "INVALID_ARGUMENT: WorkflowId is not consistent with previous operation(s)",
        failure.getStatuses(1).getMessage());
  }

  @Test
  public void failWhenMultiOperationListIsInvalid() {
    // empty operations
    StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> executeMultiOperation((builder) -> {}));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());

    // too many operations
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder().build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder().build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder().build())));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());

    // too few operations
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder.addOperations(
                            ExecuteMultiOperationRequest.Operation.newBuilder().build())));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());

    // two undefined operations
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder().build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder().build())));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());

    // two update operations
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(
                                        UpdateWorkflowExecutionRequest.newBuilder().build())
                                    .build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setUpdateWorkflow(
                                        UpdateWorkflowExecutionRequest.newBuilder().build())
                                    .build())));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());

    // two start operations
    exception =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                executeMultiOperation(
                    (builder) ->
                        builder
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        StartWorkflowExecutionRequest.newBuilder().build())
                                    .build())
                            .addOperations(
                                ExecuteMultiOperationRequest.Operation.newBuilder()
                                    .setStartWorkflow(
                                        StartWorkflowExecutionRequest.newBuilder().build())
                                    .build())));
    assertEquals(
        "INVALID_ARGUMENT: Operations have to be exactly [Start, Update]", exception.getMessage());
  }

  private ExecuteMultiOperationResponse executeMultiOperation(
      Consumer<ExecuteMultiOperationRequest.Builder> apply) {
    ExecuteMultiOperationRequest.Builder builder =
        ExecuteMultiOperationRequest.newBuilder().setNamespace(getNamespace());
    apply.accept(builder);
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .executeMultiOperation(builder.build());
  }

  private StartWorkflowExecutionRequest.Builder validStartRequest() {
    return StartWorkflowExecutionRequest.newBuilder()
        .setNamespace(getNamespace())
        .setWorkflowType(WorkflowType.newBuilder().setName(WORKFLOW_TYPE).build())
        .setTaskQueue(
            TaskQueue.newBuilder()
                .setName(testWorkflowRule.getTaskQueue())
                .setKind(TaskQueueKind.TASK_QUEUE_KIND_NORMAL)
                .build())
        .setRequestId(UUID.randomUUID().toString())
        .setWorkflowId(WORKFLOW_ID);
  }

  private UpdateWorkflowExecutionRequest.Builder validUpdateRequest() {
    return UpdateWorkflowExecutionRequest.newBuilder()
        .setNamespace(getNamespace())
        .setWaitPolicy(
            WaitPolicy.newBuilder()
                .setLifecycleStage(
                    UpdateWorkflowExecutionLifecycleStage
                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)
                .build())
        .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).build());
  }

  private String getNamespace() {
    return testWorkflowRule.getWorkflowClient().getOptions().getNamespace();
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
