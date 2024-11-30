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

import static io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.*;
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
import io.temporal.workflow.shared.TestWorkflows;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
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
              TestMultiArgWorkflowImpl.class)
          .build();

  private <T> Results updateWithStart(
      Class<T> stubClass,
      Object[] args,
      Function<T, WithStartWorkflowOperation<String>> startOperationProvider,
      BiFunction<T, WithStartWorkflowOperation<String>, WorkflowUpdateHandle<String>>
          updateHandleProvider,
      BiFunction<T, WithStartWorkflowOperation<String>, String> updateResultProvider)
      throws ExecutionException, InterruptedException {

    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    String updateName = "update";
    if (args.length > 0) {
      updateName = updateName + args.length;
    }
    UpdateOptions<String> untypedUpdateOptions =
        createUpdateOptions().toBuilder()
            .setResultClass(String.class)
            .setUpdateName(updateName)
            .build();

    // === everything typed

    // startUpdateWithStart
    T typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    WithStartWorkflowOperation<String> typedStartOp = startOperationProvider.apply(typedStub);
    WorkflowUpdateHandle<String> updHandle = updateHandleProvider.apply(typedStub, typedStartOp);

    // these will serve as the canonical results
    final String theWorkflowResult = typedStartOp.getResult();
    final String theUpdateResult = updHandle.getResult();
    assertEquals(theWorkflowResult, WorkflowStub.fromTyped(typedStub).getResult(String.class));

    // executeUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    typedStartOp = startOperationProvider.apply(typedStub);
    String updResult = updateResultProvider.apply(typedStub, typedStartOp);
    assertEquals(theUpdateResult, updResult);
    assertEquals(theWorkflowResult, typedStartOp.getResult());

    // === untyped start & typed update

    // startUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    WorkflowStub untypedStub = WorkflowStub.fromTyped(typedStub);
    WithStartWorkflowOperation<String> untypedStartOp =
        new WithStartWorkflowOperation<>(untypedStub, String.class, args);
    updHandle = updateHandleProvider.apply(typedStub, untypedStartOp);
    assertEquals(theUpdateResult, updHandle.getResultAsync().get());
    assertEquals(theUpdateResult, updHandle.getResult());
    assertEquals(theWorkflowResult, untypedStartOp.getResult());

    // executeUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStartOp = new WithStartWorkflowOperation<>(untypedStub, String.class, args);
    updResult = updateResultProvider.apply(typedStub, untypedStartOp);
    assertEquals(theUpdateResult, updResult);
    assertEquals(theWorkflowResult, untypedStartOp.getResult());

    // === typed start & untyped update

    // startUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    untypedStub = WorkflowStub.fromTyped(typedStub);
    typedStartOp = startOperationProvider.apply(typedStub);
    try {
      updHandle = untypedStub.startUpdateWithStart(untypedUpdateOptions, args, typedStartOp);
      fail("unreachable");
    } catch (IllegalStateException e) {
      assertEquals(
          "WithStartWorkflowOperation was created with different WorkflowStub", e.getMessage());
    }

    // executeUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    untypedStub = WorkflowStub.fromTyped(typedStub);
    typedStartOp = startOperationProvider.apply(typedStub);
    try {
      untypedStub.executeUpdateWithStart(untypedUpdateOptions, args, typedStartOp);
      fail("unreachable");
    } catch (IllegalStateException e) {
      assertEquals(
          "WithStartWorkflowOperation was created with different WorkflowStub", e.getMessage());
    }

    // === everything untyped

    // startUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStartOp = new WithStartWorkflowOperation<>(untypedStub, String.class, args);
    updHandle = untypedStub.startUpdateWithStart(untypedUpdateOptions, args, untypedStartOp);
    assertEquals(theUpdateResult, updHandle.getResultAsync().get());
    assertEquals(theUpdateResult, updHandle.getResult());
    assertEquals(theWorkflowResult, untypedStartOp.getResult());

    // executeUpdateWithStart
    typedStub = client.newWorkflowStub(stubClass, createWorkflowOptions());
    untypedStub = WorkflowStub.fromTyped(typedStub);
    untypedStartOp = new WithStartWorkflowOperation<>(untypedStub, String.class, args);
    updResult = untypedStub.executeUpdateWithStart(untypedUpdateOptions, args, untypedStartOp);
    assertEquals(theUpdateResult, updResult);
    assertEquals(theWorkflowResult, untypedStartOp.getResult());

    return new Results(theWorkflowResult, theUpdateResult);
  }

  @Test
  public void startWorkflowAndUpdate() throws ExecutionException, InterruptedException {
    // no arg
    Results results =
        updateWithStart(
            TestNoArgsWorkflowFunc.class,
            new Object[] {},
            (TestNoArgsWorkflowFunc stub) -> new WithStartWorkflowOperation<>(stub::func),
            (TestNoArgsWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(stub::update, createUpdateOptions(), startOp),
            (TestNoArgsWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update, createUpdateOptions(), startOp));
    assertEquals("update", results.updateResult);
    assertEquals("func", results.workflowResult);

    results =
        updateWithStart(
            TestNoArgsWorkflowProc.class,
            new Object[] {},
            (TestNoArgsWorkflowProc stub) -> new WithStartWorkflowOperation<>(stub::proc),
            (TestNoArgsWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(stub::update, createUpdateOptions(), startOp),
            (TestNoArgsWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update, createUpdateOptions(), startOp));
    assertEquals("update", results.updateResult);
    assertNull(results.workflowResult);

    // 1 arg
    results =
        updateWithStart(
            Test1ArgWorkflowFunc.class,
            new Object[] {"1"},
            (Test1ArgWorkflowFunc stub) -> new WithStartWorkflowOperation<>(stub::func1, "1"),
            (Test1ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update1, "1", createUpdateOptions(), startOp),
            (Test1ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update1, "1", createUpdateOptions(), startOp));
    assertEquals("1", results.updateResult);
    assertEquals("1", results.workflowResult);

    results =
        updateWithStart(
            Test1ArgWorkflowProc.class,
            new Object[] {"1"},
            (Test1ArgWorkflowProc stub) -> new WithStartWorkflowOperation<>(stub::proc1, "1"),
            (Test1ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update1, "1", createUpdateOptions(), startOp),
            (Test1ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update1, "1", createUpdateOptions(), startOp));
    assertEquals("1", results.updateResult);
    assertNull(results.workflowResult);

    // 2 args
    results =
        updateWithStart(
            Test2ArgWorkflowFunc.class,
            new Object[] {"1", 2},
            (Test2ArgWorkflowFunc stub) -> new WithStartWorkflowOperation<>(stub::func2, "1", 2),
            (Test2ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update2, "1", 2, createUpdateOptions(), startOp),
            (Test2ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update2, "1", 2, createUpdateOptions(), startOp));
    assertEquals("12", results.updateResult);
    assertEquals("12", results.workflowResult);

    results =
        updateWithStart(
            Test2ArgWorkflowProc.class,
            new Object[] {"1", 2},
            (Test2ArgWorkflowProc stub) -> new WithStartWorkflowOperation<>(stub::proc2, "1", 2),
            (Test2ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update2, "1", 2, createUpdateOptions(), startOp),
            (Test2ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update2, "1", 2, createUpdateOptions(), startOp));
    assertEquals("12", results.updateResult);
    assertNull(results.workflowResult);

    // 3 args
    results =
        updateWithStart(
            Test3ArgWorkflowFunc.class,
            new Object[] {"1", 2, 3},
            (Test3ArgWorkflowFunc stub) -> new WithStartWorkflowOperation<>(stub::func3, "1", 2, 3),
            (Test3ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update3, "1", 2, 3, createUpdateOptions(), startOp),
            (Test3ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update3, "1", 2, 3, createUpdateOptions(), startOp));
    assertEquals("123", results.updateResult);
    assertEquals("123", results.workflowResult);

    results =
        updateWithStart(
            Test3ArgWorkflowProc.class,
            new Object[] {"1", 2, 3},
            (Test3ArgWorkflowProc stub) -> new WithStartWorkflowOperation<>(stub::proc3, "1", 2, 3),
            (Test3ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update3, "1", 2, 3, createUpdateOptions(), startOp),
            (Test3ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update3, "1", 2, 3, createUpdateOptions(), startOp));
    assertEquals("123", results.updateResult);
    assertNull(results.workflowResult);

    // 4 args
    results =
        updateWithStart(
            Test4ArgWorkflowFunc.class,
            new Object[] {"1", 2, 3, 4},
            (Test4ArgWorkflowFunc stub) ->
                new WithStartWorkflowOperation<>(stub::func4, "1", 2, 3, 4),
            (Test4ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update4, "1", 2, 3, 4, createUpdateOptions(), startOp),
            (Test4ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update4, "1", 2, 3, 4, createUpdateOptions(), startOp));
    assertEquals("1234", results.updateResult);
    assertEquals("1234", results.workflowResult);

    results =
        updateWithStart(
            Test4ArgWorkflowProc.class,
            new Object[] {"1", 2, 3, 4},
            (Test4ArgWorkflowProc stub) ->
                new WithStartWorkflowOperation<>(stub::proc4, "1", 2, 3, 4),
            (Test4ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update4, "1", 2, 3, 4, createUpdateOptions(), startOp),
            (Test4ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update4, "1", 2, 3, 4, createUpdateOptions(), startOp));
    assertEquals("1234", results.updateResult);
    assertNull(results.workflowResult);

    // 5 args
    results =
        updateWithStart(
            Test5ArgWorkflowFunc.class,
            new Object[] {"1", 2, 3, 4, 5},
            (Test5ArgWorkflowFunc stub) ->
                new WithStartWorkflowOperation<>(stub::func5, "1", 2, 3, 4, 5),
            (Test5ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update5, "1", 2, 3, 4, 5, createUpdateOptions(), startOp),
            (Test5ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update5, "1", 2, 3, 4, 5, createUpdateOptions(), startOp));
    assertEquals("12345", results.updateResult);
    assertEquals("12345", results.workflowResult);

    results =
        updateWithStart(
            Test5ArgWorkflowProc.class,
            new Object[] {"1", 2, 3, 4, 5},
            (Test5ArgWorkflowProc stub) ->
                new WithStartWorkflowOperation<>(stub::proc5, "1", 2, 3, 4, 5),
            (Test5ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update5, "1", 2, 3, 4, 5, createUpdateOptions(), startOp),
            (Test5ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update5, "1", 2, 3, 4, 5, createUpdateOptions(), startOp));
    assertEquals("12345", results.updateResult);
    assertNull(results.workflowResult);

    // 6 args
    results =
        updateWithStart(
            Test6ArgWorkflowFunc.class,
            new Object[] {"1", 2, 3, 4, 5, 6},
            (Test6ArgWorkflowFunc stub) ->
                new WithStartWorkflowOperation<>(stub::func6, "1", 2, 3, 4, 5, 6),
            (Test6ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update6, "1", 2, 3, 4, 5, 6, createUpdateOptions(), startOp),
            (Test6ArgWorkflowFunc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update6, "1", 2, 3, 4, 5, 6, createUpdateOptions(), startOp));
    assertEquals("123456", results.updateResult);
    assertEquals("123456", results.workflowResult);

    results =
        updateWithStart(
            Test6ArgWorkflowProc.class,
            new Object[] {"1", 2, 3, 4, 5, 6},
            (Test6ArgWorkflowProc stub) ->
                new WithStartWorkflowOperation<>(stub::proc6, "1", 2, 3, 4, 5, 6),
            (Test6ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.startUpdateWithStart(
                    stub::update6, "1", 2, 3, 4, 5, 6, createUpdateOptions(), startOp),
            (Test6ArgWorkflowProc stub, WithStartWorkflowOperation<String> startOp) ->
                WorkflowClient.executeUpdateWithStart(
                    stub::update6, "1", 2, 3, 4, 5, 6, createUpdateOptions(), startOp));
    assertEquals("123456", results.updateResult);
    assertNull(results.workflowResult);
  }

  @Test
  public void nullUpdateResult() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.TestUpdatedWorkflow workflow =
        workflowClient.newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, options);

    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    WorkflowUpdateHandle<String> updHandle =
        WorkflowClient.startUpdateWithStart(
            workflow::update, "Hello Update", createUpdateOptions(), startOp);

    assertNull(updHandle.getResult());
    assertNull(updHandle.getResultAsync().get());

    assertEquals("Hello Update", startOp.getResult());
    assertEquals("Hello Update", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void onlySendUpdateWhenWorkflowIsAlreadyRunning()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // first, start workflow
    WorkflowOptions options =
        createWorkflowOptions().toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    // then, send update-with-start
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    WorkflowUpdateHandle<String> updHandle =
        WorkflowClient.startUpdateWithStart(
            workflow::update, 0, "Hello Update", createUpdateOptions(), startOp);

    assertEquals(execution.getRunId(), updHandle.getExecution().getRunId());
    assertEquals("Hello Update", updHandle.getResult());
    assertEquals("Hello Update", updHandle.getResultAsync().get());

    workflow.complete();

    assertEquals("Hello Update complete", startOp.getResult());
    assertEquals("Hello Update complete", WorkflowStub.fromTyped(workflow).getResult(String.class));
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
    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);

    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);
    WorkflowUpdateHandle<String> updHandle =
        WorkflowClient.startUpdateWithStart(
            workflow::update, 0, "Hello Update", createUpdateOptions(), startOp);

    assertEquals("run_id", updHandle.getExecution().getRunId());
  }

  @Test
  public void timeoutError() {
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(5, TimeUnit.SECONDS);

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    final AtomicReference<WorkflowServiceException> exception = new AtomicReference<>();
    ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(1);
    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        .run(
            () ->
                exception.set(
                    assertThrows(
                        WorkflowServiceException.class,
                        () ->
                            WorkflowClient.startUpdateWithStart(
                                workflow::update,
                                0,
                                "Hello Update",
                                UpdateOptions.newBuilder(String.class)
                                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                                    .build(),
                                startOp))));
    assertEquals(options.getWorkflowId(), exception.get().getExecution().getWorkflowId());
    WorkflowServiceException cause =
        (WorkflowUpdateTimeoutOrCancelledException) exception.get().getCause();
    assertEquals(options.getWorkflowId(), cause.getExecution().getWorkflowId());
  }

  @Test
  public void failWhenWorkflowAlreadyRunning() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // first, start workflow
    WorkflowOptions options1 = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow1 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options1);
    WorkflowClient.start(workflow1::execute);

    // then, send update-with-start
    WorkflowOptions options2 =
        createWorkflowOptions().toBuilder().setWorkflowId(options1.getWorkflowId()).build();
    TestWorkflows.WorkflowWithUpdate workflow2 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options2);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow2::execute);

    WorkflowServiceException exception =
        assertThrows(
            WorkflowServiceException.class,
            () ->
                WorkflowClient.startUpdateWithStart(
                    workflow2::update,
                    0,
                    "Hello Update",
                    UpdateOptions.newBuilder(String.class)
                        .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                        .build(),
                    startOp));
    StatusRuntimeException cause = (StatusRuntimeException) exception.getCause();
    assertEquals(Status.ALREADY_EXISTS.getCode(), cause.getStatus().getCode());
  }

  @Test
  public void failWhenUpdatedIsRejected() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.WorkflowWithUpdate.class, createWorkflowOptions());
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    assertThrows(
        WorkflowUpdateException.class,
        () ->
            WorkflowClient.startUpdateWithStart(
                workflow::update,
                -1, // cause for rejection
                "Hello Update",
                UpdateOptions.newBuilder(String.class)
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build(),
                startOp).getResult());
  }

  @Test
  public void failWhenStartOperationUsedAgain() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.WorkflowWithUpdate.class, createWorkflowOptions());
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);
    WorkflowClient.startUpdateWithStart(
        workflow::update,
        0,
        "Hello Update",
        UpdateOptions.newBuilder(String.class)
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build(),
        startOp);

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
              .build(),
          startOp); // re-use same `startOp`
      fail("unreachable");
    } catch (IllegalStateException e) {
      assertEquals(e.getMessage(), "WithStartWorkflowOperation was already executed");
    }
  }

  @Test
  public void failWhenUpdateNamesDoNotMatch() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.TestUpdatedWorkflow workflow =
        workflowClient.newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, options);

    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    try {
      WorkflowUpdateHandle<String> updHandle =
          WorkflowClient.startUpdateWithStart(
              workflow::update,
              "Hello Update",
              createUpdateOptions().toBuilder()
                  .setUpdateName("custom_update_name") // custom name!
                  .build(),
              startOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(
          e.getMessage(),
          "Update name in the options doesn't match the method name: custom_update_name != testUpdate");
    }
  }

  @Test
  public void failServerSideWhenStartIsInvalid() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = // using invalid reuse/conflict policies
        createWorkflowOptions().toBuilder()
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING)
            .build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
              .build(),
          startOp);
      fail("unreachable");
    } catch (WorkflowServiceException e) {
      assertTrue(
          e.getCause().getMessage().contains("WORKFLOW_ID_REUSE_POLICY_TERMINATE_IF_RUNNING"));
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failServerSideWhenUpdateIsInvalid() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions().toBuilder().build();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
              .setFirstExecutionRunId(UUID.randomUUID().toString())
              .build(),
          startOp);
      fail("unreachable");
    } catch (WorkflowServiceException e) {
      assertTrue(e.getCause().getMessage().contains("FirstExecutionRunId"));
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failClientSideWhenUpdateIsInvalid() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class).build(), // invalid
          startOp);
      fail("unreachable");
    } catch (IllegalStateException e) {
      assertEquals(e.getMessage(), "waitForStage must not be null");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenWorkflowOptionsIsMissing() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub("workflow-id"); // no WorkflowOptions!
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflowStub, String.class);

    UpdateOptions<String> updateOptions =
        UpdateOptions.newBuilder(String.class)
            .setUpdateName("update")
            .setResultClass(String.class)
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    try {
      workflowStub.startUpdateWithStart(updateOptions, new Object[] {0, "Hello Update"}, startOp);
    } catch (IllegalStateException e) {
      assertEquals(e.getMessage(), "Required parameter WorkflowOptions is missing in WorkflowStub");
    }
  }

  @Test
  public void failWhenConflictPolicyIsMissing() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            TestWorkflows.WorkflowWithUpdate.class.getSimpleName(),
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                .toBuilder() // no WorkflowIdConflictPolicy!
                .setWorkflowId(UUID.randomUUID().toString())
                .build());
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflowStub, String.class);

    UpdateOptions<String> updateOptions =
        UpdateOptions.newBuilder(String.class)
            .setUpdateName("update")
            .setResultClass(String.class)
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build();

    try {
      workflowStub.startUpdateWithStart(updateOptions, new Object[] {0, "Hello Update"}, startOp);
    } catch (IllegalStateException e) {
      assertEquals(
          e.getMessage(),
          "Required parameter WorkflowIdConflictPolicy in WorkflowOptions is missing in WorkflowStub");
    }
  }

  @Test
  public void failWhenUsingNonUpdateMethod() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::execute);

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::execute, // incorrect!
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
              .build(),
          startOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "Method 'execute' is not an UpdateMethod");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenUsingNonStartMethod() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    WithStartWorkflowOperation<String> startOp =
        new WithStartWorkflowOperation<>(workflow::update, 0, "Hello Update"); // incorrect!

    try {
      WorkflowClient.startUpdateWithStart(
          workflow::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
              .build(),
          startOp);
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(e.getMessage(), "Method 'update' is not a WorkflowMethod");
    }

    ensureNoWorkflowStarted(workflowClient, options.getWorkflowId());
  }

  @Test
  public void failWhenMixingStubs() {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    WorkflowOptions options = createWorkflowOptions();
    TestWorkflows.TestUpdatedWorkflow stub1 =
        workflowClient.newWorkflowStub(TestWorkflows.TestUpdatedWorkflow.class, options);
    WithStartWorkflowOperation<String> startOp = new WithStartWorkflowOperation<>(stub1::execute);

    TestWorkflows.WorkflowWithUpdate stub2 =
        workflowClient.newWorkflowStub(TestWorkflows.WorkflowWithUpdate.class, options);
    try {
      WorkflowClient.startUpdateWithStart(
          stub2::update,
          0,
          "Hello Update",
          UpdateOptions.newBuilder(String.class)
              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
              .build(),
          startOp); // for stub1!
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertEquals(
          e.getMessage(), "WithStartWorkflowOperation invoked on different workflow stubs");
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

  private <T> UpdateOptions<T> createUpdateOptions() {
    return UpdateOptions.<T>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build();
  }

  private WorkflowOptions createWorkflowOptions() {
    return SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
        .toBuilder()
        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
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

  static class Results {
    final Object workflowResult;
    final Object updateResult;

    public Results(Object workflowResult, Object updateResult) {
      this.workflowResult = workflowResult;
      this.updateResult = updateResult;
    }
  }
}
