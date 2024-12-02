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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.Scope;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.update.v1.UpdateRef;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.Rule;
import org.junit.Test;

public class UpdateWithStartTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(
              WorkflowWithUpdateImpl.class,
              TestUpdatedWorkflowImpl.class,
              TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl.class)
          .build();

  @Test
  public void startAndSendUpdateTogether() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);

    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 1, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowUpdateHandle<String> handle1 =
        WorkflowClient.updateWithStart(workflow::execute, updateOp);
    assertEquals(options.getWorkflowId(), handle1.getExecution().getWorkflowId());
    assertEquals("Hello Update", handle1.getResultAsync().get());

    WorkflowUpdateHandle<String> handle2 = updateOp.getUpdateHandle().get();
    assertEquals(updateOp.getResult(), handle2.getResultAsync().get());

    workflow.complete();

    assertEquals("Hello Update complete", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void startAndSendUpdateTogetherUsingUntypedWorkflowOperation()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);

    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(
                "update", String.class, new Object[] {1, "Hello Update"}) // untyped!
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowUpdateHandle<String> handle1 =
        WorkflowClient.updateWithStart(workflow::execute, updateOp);
    assertEquals("Hello Update", handle1.getResultAsync().get());

    WorkflowUpdateHandle<String> handle2 = updateOp.getUpdateHandle().get();
    assertEquals(updateOp.getResult(), handle2.getResultAsync().get());

    workflow.complete();

    assertEquals("Hello Update complete", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void startAndSendUpdateTogetherWithNullUpdateResult()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.TestUpdatedWorkflow workflow =
        workflowClient.newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, options);

    UpdateWithStartWorkflowOperation<Void> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowUpdateHandle<Void> handle1 =
        WorkflowClient.updateWithStart(workflow::execute, updateOp);
    assertNull(handle1.getResultAsync().get());

    WorkflowUpdateHandle<Void> handle2 = updateOp.getUpdateHandle().get();
    assertEquals(updateOp.getResult(), handle2.getResultAsync().get());

    assertEquals("Hello Update", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void onlySendUpdateWhenWorkflowIsAlreadyRunning()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // first, start workflow
    WorkflowOptions options1 = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow1 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options1);
    WorkflowExecution execution1 = WorkflowClient.start(workflow1::execute);

    // then, send Update
    WorkflowOptions options2 =
        createOptions().toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .setWorkflowId(options1.getWorkflowId())
            .build();
    TestWorkflows.WorkflowWithUpdate workflow2 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options2);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow2::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowUpdateHandle<String> updHandle =
        WorkflowClient.updateWithStart(workflow2::execute, updateOp);
    assertEquals(execution1.getRunId(), updHandle.getExecution().getRunId());
    assertEquals(updateOp.getResult(), updHandle.getResultAsync().get());

    workflow2.complete();
    assertEquals(
        "Hello Update complete", WorkflowStub.fromTyped(workflow2).getResult(String.class));
  }

  @Test
  public void startVariousFuncs() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    BiFunction<Functions.Func1<Integer, String>, Integer, UpdateWithStartWorkflowOperation<String>>
        newUpdateOp =
            (request, input) ->
                UpdateWithStartWorkflowOperation.newBuilder(request, input)
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build();

    // no arg
    TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc stubF =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.TestNoArgsWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp0 = newUpdateOp.apply(stubF::update, 0);
    WorkflowUpdateHandle<String> handle0 = WorkflowClient.updateWithStart(stubF::func, updateOp0);

    // 1 arg
    TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc stubF1 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp1 = newUpdateOp.apply(stubF1::update, 1);
    WorkflowUpdateHandle<String> handle1 =
        WorkflowClient.updateWithStart(stubF1::func1, "1", updateOp1);

    // 2 args
    TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc stubF2 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test2ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp2 = newUpdateOp.apply(stubF2::update, 2);
    WorkflowUpdateHandle<String> handle2 =
        WorkflowClient.updateWithStart(stubF2::func2, "1", 2, updateOp2);

    // 3 args
    TestMultiArgWorkflowFunctions.Test3ArgWorkflowFunc stubF3 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test3ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp3 = newUpdateOp.apply(stubF3::update, 3);
    WorkflowUpdateHandle<String> handle3 =
        WorkflowClient.updateWithStart(stubF3::func3, "1", 2, 3, updateOp3);

    // 4 args
    TestMultiArgWorkflowFunctions.Test4ArgWorkflowFunc stubF4 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test4ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp4 = newUpdateOp.apply(stubF4::update, 4);
    WorkflowUpdateHandle<String> handle4 =
        WorkflowClient.updateWithStart(stubF4::func4, "1", 2, 3, 4, updateOp4);

    // 5 args
    TestMultiArgWorkflowFunctions.Test5ArgWorkflowFunc stubF5 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test5ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp5 = newUpdateOp.apply(stubF5::update, 5);
    WorkflowUpdateHandle<String> handle5 =
        WorkflowClient.updateWithStart(stubF5::func5, "1", 2, 3, 4, 5, updateOp5);

    // 6 args
    TestMultiArgWorkflowFunctions.Test6ArgWorkflowFunc stubF6 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test6ArgWorkflowFunc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp6 = newUpdateOp.apply(stubF6::update, 6);
    WorkflowUpdateHandle<String> handle6 =
        WorkflowClient.updateWithStart(stubF6::func6, "1", 2, 3, 4, 5, 6, updateOp6);

    assertEquals("0", handle0.getResultAsync().get());
    assertEquals("func", WorkflowStub.fromTyped(stubF).getResult(String.class));
    assertEquals("1", handle1.getResultAsync().get());
    assertEquals("1", WorkflowStub.fromTyped(stubF1).getResult(String.class));
    assertEquals("2", handle2.getResultAsync().get());
    assertEquals("12", WorkflowStub.fromTyped(stubF2).getResult(String.class));
    assertEquals("3", handle3.getResultAsync().get());
    assertEquals("123", WorkflowStub.fromTyped(stubF3).getResult(String.class));
    assertEquals("4", handle4.getResultAsync().get());
    assertEquals("1234", WorkflowStub.fromTyped(stubF4).getResult(String.class));
    assertEquals("5", handle5.getResultAsync().get());
    assertEquals("12345", WorkflowStub.fromTyped(stubF5).getResult(String.class));
    assertEquals("6", handle6.getResultAsync().get());
    assertEquals("123456", WorkflowStub.fromTyped(stubF6).getResult(String.class));
  }

  @Test
  public void startVariousProcs() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    BiFunction<Functions.Func1<Integer, String>, Integer, UpdateWithStartWorkflowOperation<String>>
        newUpdateOp =
            (request, input) ->
                UpdateWithStartWorkflowOperation.newBuilder(request, input)
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build();

    // no arg
    TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc stubProc =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.TestNoArgsWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp0 = newUpdateOp.apply(stubProc::update, 0);
    WorkflowUpdateHandle<String> handle0 =
        WorkflowClient.updateWithStart(stubProc::proc, updateOp0);

    // 1 arg
    TestMultiArgWorkflowFunctions.Test1ArgWorkflowProc stubProc1 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test1ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp1 = newUpdateOp.apply(stubProc1::update, 1);
    WorkflowUpdateHandle<String> handle1 =
        WorkflowClient.updateWithStart(stubProc1::proc1, "1", updateOp1);

    // 2 args
    TestMultiArgWorkflowFunctions.Test2ArgWorkflowProc stubProc2 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test2ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp2 = newUpdateOp.apply(stubProc2::update, 2);
    WorkflowUpdateHandle<String> handle2 =
        WorkflowClient.updateWithStart(stubProc2::proc2, "1", 2, updateOp2);

    // 3 args
    TestMultiArgWorkflowFunctions.Test3ArgWorkflowProc stubProc3 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test3ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp3 = newUpdateOp.apply(stubProc3::update, 3);
    WorkflowUpdateHandle<String> handle3 =
        WorkflowClient.updateWithStart(stubProc3::proc3, "1", 2, 3, updateOp3);

    // 4 args
    TestMultiArgWorkflowFunctions.Test4ArgWorkflowProc stubProc4 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test4ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp4 = newUpdateOp.apply(stubProc4::update, 4);
    WorkflowUpdateHandle<String> handle4 =
        WorkflowClient.updateWithStart(stubProc4::proc4, "1", 2, 3, 4, updateOp4);

    // 5 args
    TestMultiArgWorkflowFunctions.Test5ArgWorkflowProc stubProc5 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test5ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp5 = newUpdateOp.apply(stubProc5::update, 5);
    WorkflowUpdateHandle<String> handle5 =
        WorkflowClient.updateWithStart(stubProc5::proc5, "1", 2, 3, 4, 5, updateOp5);

    // 6 args
    TestMultiArgWorkflowFunctions.Test6ArgWorkflowProc stubProc6 =
        workflowClient.newWorkflowStub(
            TestMultiArgWorkflowFunctions.Test6ArgWorkflowProc.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp6 = newUpdateOp.apply(stubProc6::update, 6);
    WorkflowUpdateHandle<String> handle6 =
        WorkflowClient.updateWithStart(stubProc6::proc6, "1", 2, 3, 4, 5, 6, updateOp6);

    assertEquals("0", handle0.getResultAsync().get());
    assertEquals("1", handle1.getResultAsync().get());
    assertEquals("2", handle2.getResultAsync().get());
    assertEquals("3", handle3.getResultAsync().get());
    assertEquals("4", handle4.getResultAsync().get());
    assertEquals("5", handle5.getResultAsync().get());
    assertEquals("6", handle6.getResultAsync().get());
  }

  @Test
  public void retryUntilDurable() {
    WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(blockingStub.withOption(any(), any())).thenReturn(blockingStub);
    when(blockingStub.withDeadline(any())).thenReturn(blockingStub);

    Scope scope = mock(Scope.class);
    when(scope.tagged(any())).thenReturn(scope);

    WorkflowServiceStubs client = mock(WorkflowServiceStubs.class);
    when(client.getServerCapabilities())
        .thenReturn(() -> GetSystemInfoResponse.Capabilities.newBuilder().build());
    when(client.blockingStub()).thenReturn(blockingStub);
    when(client.getOptions())
        .thenReturn(WorkflowServiceStubsOptions.newBuilder().setMetricsScope(scope).build());

    when(blockingStub.executeMultiOperation(any()))
        .thenReturn( // 1st response: empty response, Update is not durable yet, client retries
            ExecuteMultiOperationResponse.newBuilder()
                .addResponses(
                    ExecuteMultiOperationResponse.Response.newBuilder()
                        .setStartWorkflow(StartWorkflowExecutionResponse.newBuilder()))
                .addResponses(
                    ExecuteMultiOperationResponse.Response.newBuilder()
                        .setUpdateWorkflow(UpdateWorkflowExecutionResponse.newBuilder()))
                .build())
        .thenReturn( // 2nd response: non-empty response, Update is durable
            ExecuteMultiOperationResponse.newBuilder()
                .addResponses(
                    ExecuteMultiOperationResponse.Response.newBuilder()
                        .setStartWorkflow(StartWorkflowExecutionResponse.newBuilder()))
                .addResponses(
                    ExecuteMultiOperationResponse.Response.newBuilder()
                        .setUpdateWorkflow(
                            UpdateWorkflowExecutionResponse.newBuilder()
                                .setUpdateRef(
                                    UpdateRef.newBuilder()
                                        .setWorkflowExecution(
                                            WorkflowExecution.newBuilder().setRunId("run_id")))
                                .setStage(
                                    UpdateWorkflowExecutionLifecycleStage
                                        .UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED)))
                .build())
        .thenThrow(new IllegalStateException("should not be reached"));

    WorkflowClient workflowClient =
        WorkflowClient.newInstance(client, WorkflowClientOptions.newBuilder().build());
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowUpdateHandle<String> updateHandle =
        WorkflowClient.updateWithStart(workflow::execute, updateOp);

    assertEquals("run_id", updateHandle.getExecution().getRunId());
  }

  @Test
  public void timeoutError() {
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(5, TimeUnit.SECONDS);

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    final AtomicReference<WorkflowServiceException> exception = new AtomicReference<>();
    ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1);
    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        WorkflowServiceException.class,
                        () -> WorkflowClient.updateWithStart(workflow::execute, updateOp))));
    assertEquals(workflowId, exception.get().getExecution().getWorkflowId());
    WorkflowServiceException cause =
        (WorkflowUpdateTimeoutOrCancelledException) exception.get().getCause();
    assertEquals(workflowId, cause.getExecution().getWorkflowId());
  }

  @Test
  public void failWhenWorkflowAlreadyRunning() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // first, start workflow
    WorkflowOptions options1 = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow1 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options1);
    WorkflowClient.start(workflow1::execute);

    // then, send Update-with-Start
    WorkflowOptions options2 =
        createOptions().toBuilder().setWorkflowId(options1.getWorkflowId()).build();
    TestWorkflows.WorkflowWithUpdate workflow2 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options2);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow2::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    WorkflowServiceException exception =
        assertThrows(
            WorkflowServiceException.class,
            () -> WorkflowClient.updateWithStart(workflow2::execute, updateOp));
    StatusRuntimeException cause = (StatusRuntimeException) exception.getCause();
    assertEquals(Status.ALREADY_EXISTS.getCode(), cause.getStatus().getCode());
  }

  @Test
  public void failWhenUpdatedIsRejected() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, -1, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();

    assertThrows(
        WorkflowUpdateException.class,
        () -> WorkflowClient.updateWithStart(workflow::execute, updateOp).getResult());
  }

  @Test
  public void failWhenUpdateOperationUsedAgain() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, createOptions());
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();
    WorkflowClient.updateWithStart(workflow::execute, updateOp);

    try {
      WorkflowClient.updateWithStart(workflow::execute, updateOp);
      fail("unreachable");
    } catch (IllegalStateException e) {
      assertEquals(e.getMessage(), "UpdateWithStartWorkflowOperation was already executed");
    }
  }

  @Test
  public void failServerSideWhenStartOptionIsInvalid() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = // using invalid reuse/conflict policies
        createOptions().toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 0, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();

    try {
      WorkflowClient.updateWithStart(workflow::execute, updateOp);
      fail("unreachable");
    } catch (WorkflowServiceException e) {
      assertTrue(
          e.getCause().getMessage().contains("WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING"));
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failClientSideWhenUpdateOptionIsInvalid() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp = // without wait stage
        UpdateWithStartWorkflowOperation.newBuilder(workflow::update, 0, "Hello Update").build();

    try {
      WorkflowClient.updateWithStart(workflow::execute, updateOp);
      fail("unreachable");
    } catch (WorkflowServiceException e) {
      assertEquals(e.getCause().getMessage(), "waitForStage must not be null");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenUsingNonUpdateMethod() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(workflow::execute) // incorrect!
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();

    try {
      WorkflowClient.updateWithStart(workflow::execute, updateOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "Method 'execute' is not an UpdateMethod");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenUsingNonStartMethod() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    UpdateWithStartWorkflowOperation<String> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(
                workflow::update, 0, "Hello Update") // incorrect!
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();

    try {
      WorkflowClient.updateWithStart(workflow::update, 0, "Hello Update", updateOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "Method 'update' is not a WorkflowMethod");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenMixingStubs() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createOptions();
    TestWorkflows.TestUpdatedWorkflow stub1 =
        workflowClient.newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, options);
    UpdateWithStartWorkflowOperation<Void> updateOp =
        UpdateWithStartWorkflowOperation.newBuilder(stub1::update, "Hello Update")
            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
            .build();

    TestWorkflows.WorkflowWithUpdate stub2 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    try {
      WorkflowClient.updateWithStart(stub2::execute, updateOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(
          e.getMessage(), "UpdateWithStartWorkflowOperation invoked on different workflow stubs");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  private static void ensureNoWorkflowStarted(WorkflowClient workflowClient, String workflowId) {
    try {
      workflowClient.fetchHistory(workflowId);
      fail("unreachable");
    } catch (StatusRuntimeException e) {
      assertEquals(e.getStatus().getCode(), Status.NOT_FOUND.getCode());
    }
  }

  private WorkflowOptions createOptions() {
    return SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
        .toBuilder()
        .setWorkflowId(UUID.randomUUID().toString())
        .build();
  }

  public static class WorkflowWithUpdateImpl implements TestWorkflows.WorkflowWithUpdate {
    String state = "initial";
    CompletablePromise<Void> promise = Workflow.newPromise();

    @Override
    public String execute() {
      promise.get();
      return state;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      state = value;
      return value;
    }

    @Override
    public void updateValidator(Integer index, String value) {
      if (index < 0) {
        throw new RuntimeException("Rejecting update");
      }
    }

    @Override
    public void complete() {
      state += " complete";
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }

  public static class TestUpdatedWorkflowImpl implements TestWorkflows.TestUpdatedWorkflow {

    private String state;

    @Override
    public String execute() {
      return state;
    }

    @Override
    public void update(String arg) {
      this.state = arg;
    }
  }
}
