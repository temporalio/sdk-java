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

package io.temporal.client;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.Experimental;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import io.temporal.workflow.Functions.Proc;
import io.temporal.workflow.Functions.Proc1;
import io.temporal.workflow.Functions.Proc2;
import io.temporal.workflow.Functions.Proc3;
import io.temporal.workflow.Functions.Proc4;
import io.temporal.workflow.Functions.Proc5;
import io.temporal.workflow.Functions.Proc6;
import io.temporal.workflow.WorkflowMethod;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Client to the Temporal service used to start and query workflows by external processes. Also, it
 * supports creation of {@link ActivityCompletionClient} instances used to complete activities
 * asynchronously. Do not create this object for each request, keep it for the duration of the
 * process.
 *
 * <p>Given a workflow interface executing a workflow requires initializing a {@link
 * io.temporal.client.WorkflowClient} instance, creating a client side stub to the workflow, and
 * then calling a method annotated with {@literal @}{@link WorkflowMethod}.
 *
 * <pre><code>
 * WorkflowClient workflowClient =  WorkflowClient.newInstance(service, clientOptions);
 * // Create a workflow stub.
 * FileProcessingWorkflow workflow = workflowClient.newWorkflowStub(FileProcessingWorkflow.class);
 * </code></pre>
 *
 * There are two ways to start workflow execution: synchronously and asynchronously. Synchronous
 * invocation starts a workflow and then waits for its completion. If the process that started the
 * workflow crashes or stops waiting, the workflow continues executing. Because workflows are
 * potentially long-running, and crashes of clients happen, it is not very commonly found in
 * production use. Asynchronous start initiates workflow execution and immediately returns to the
 * caller. This is the most common way to start workflows in production code.
 *
 * <p>Synchronous start:
 *
 * <pre><code>
 * // Start a workflow and wait for a result.
 * // Note that if the waiting process is killed, the workflow will continue executing.
 * String result = workflow.processFile(workflowArgs);
 * </code></pre>
 *
 * Asynchronous when the workflow result is not needed:
 *
 * <pre><code>
 * // Returns as soon as the workflow is scheduled to start on the server.
 * WorkflowExecution workflowExecution = WorkflowClient.start(workflow::processFile, workflowArgs);
 *
 * System.out.println("Started process file workflow with workflowId=\"" + workflowExecution.getWorkflowId()
 *                     + "\" and runId=\"" + workflowExecution.getRunId() + "\"");
 * </code></pre>
 *
 * Asynchronous when the result is needed:
 *
 * <pre><code>
 * // Returns a CompletableFuture&lt;String&gt; on the workflow result as soon as the workflow is scheduled to start on the server.
 * CompletableFuture&lt;String&gt; result = WorkflowClient.execute(workflow::helloWorld, "User");
 * </code></pre>
 *
 * If you need to wait for a workflow completion after an asynchronous start, maybe even from a
 * different process, the simplest way is to call the blocking version again. If {@link
 * WorkflowOptions#getWorkflowIdReusePolicy()} is not {@code AllowDuplicate} then instead of
 * throwing {@link WorkflowExecutionAlreadyStarted}, it reconnects to an existing workflow and waits
 * for its completion. The following example shows how to do this from a different process than the
 * one that started the workflow. All this process needs is a {@code WorkflowId}.
 *
 * <pre><code>
 * FileProcessingWorkflow workflow = workflowClient.newWorkflowStub(FileProcessingWorkflow.class, workflowId);
 * // Returns result potentially waiting for workflow to complete.
 * String result = workflow.processFile(workflowArgs);
 * </code></pre>
 *
 * @see io.temporal.workflow.Workflow
 * @see Activity
 * @see io.temporal.worker.Worker
 */
public interface WorkflowClient {

  /** Use this constant as a query type to get a workflow stack trace. */
  String QUERY_TYPE_STACK_TRACE = "__stack_trace";
  /** Replays workflow to the current state and returns empty result or error if replay failed. */
  String QUERY_TYPE_REPLAY_ONLY = "__replay_only";

  /**
   * Creates client that connects to an instance of the Temporal Service.
   *
   * @param service client to the Temporal Service endpoint.
   */
  static WorkflowClient newInstance(WorkflowServiceStubs service) {
    return WorkflowClientInternalImpl.newInstance(
        service, WorkflowClientOptions.getDefaultInstance());
  }

  /**
   * Creates client that connects to an instance of the Temporal Service.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.common.converter.DataConverter}er override) for
   *     configuring client.
   */
  static WorkflowClient newInstance(WorkflowServiceStubs service, WorkflowClientOptions options) {
    return WorkflowClientInternalImpl.newInstance(service, options);
  }

  WorkflowClientOptions getOptions();

  WorkflowServiceStubs getWorkflowServiceStubs();

  /**
   * Creates workflow client stub that can be used to start a single workflow execution. The first
   * call must be to a method annotated with @WorkflowMethod. After workflow is started it can be
   * also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
   * stub should be created for each new one.
   *
   * @param workflowInterface interface that given workflow implements
   * @param options options that will be used to configure and start a new workflow. At least {@link
   *     WorkflowOptions.Builder#setTaskQueue(String)} needs to be specified.
   * @return Stub that implements workflowInterface and can be used to start workflow and signal or
   *     query it after the start.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options);

  /**
   * Creates workflow client stub for a known execution. Use it to send signals or queries to a
   * running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param workflowInterface interface that given workflow implements.
   * @param workflowId Workflow id.
   * @return Stub that implements workflowInterface and can be used to signal or query it.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId);

  /**
   * Creates workflow client stub for a known execution. Use it to send signals, updates, or queries
   * to a running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param workflowInterface interface that given workflow implements.
   * @param workflowId Workflow id.
   * @param runId Run id of the workflow execution.
   * @return Stub that implements workflowInterface and can be used to signal, update, or query it.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId, Optional<String> runId);

  /**
   * Creates workflow untyped client stub that can be used to start a single workflow execution. Use
   * it to send signals or queries to a running workflow. Do not call methods annotated
   * with @WorkflowMethod.
   *
   * @param workflowId Workflow id.
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  WorkflowStub newUntypedWorkflowStub(String workflowId);

  /**
   * Creates workflow untyped client stub that can be used to start a single workflow execution.
   * After workflow is started it can be also used to send signals or queries to it. IMPORTANT! Stub
   * is per workflow instance. So new stub should be created for each new one.
   *
   * @param workflowType name of the workflow type
   * @param options options used to start a workflow through returned stub
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  WorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions options);

  /**
   * Creates workflow untyped client stub for a known execution. Use it to send signals or queries
   * to a running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param workflowId workflow id and optional run id for execution
   * @param runId runId of the workflow execution. If not provided the last workflow with the given
   *     workflowId is assumed.
   * @param workflowType type of the workflow. Optional as it is used for error reporting only.
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  WorkflowStub newUntypedWorkflowStub(
      String workflowId, Optional<String> runId, Optional<String> workflowType);

  /**
   * Creates workflow untyped client stub for a known execution. Use it to send signals or queries
   * to a running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param execution workflow id and optional run id for execution
   * @param workflowType type of the workflow. Optional as it is used for error reporting only.
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  WorkflowStub newUntypedWorkflowStub(WorkflowExecution execution, Optional<String> workflowType);

  /**
   * Creates new {@link ActivityCompletionClient} that can be used to complete activities
   * asynchronously. Only relevant for activity implementations that called {@link
   * ActivityExecutionContext#doNotCompleteOnReturn()}.
   *
   * <p>TODO: Activity completion options with retries and timeouts.
   */
  ActivityCompletionClient newActivityCompletionClient();

  /**
   * Creates BatchRequest that can be used to signal an existing workflow or start a new one if not
   * running. The batch before invocation must contain exactly two operations. One annotated
   * with @WorkflowMethod and another with @SignalMethod.
   *
   * @return batch request used to call {@link #signalWithStart(BatchRequest)}
   */
  BatchRequest newSignalWithStartRequest();

  /**
   * Invoke SignalWithStart operation.
   *
   * @param signalWithStartBatch Must be created with {@link #newSignalWithStartRequest()}
   * @return workflowId and runId of the signaled or started workflow.
   */
  WorkflowExecution signalWithStart(BatchRequest signalWithStartBatch);

  /**
   * A wrapper around {WorkflowServiceStub#listWorkflowExecutions(ListWorkflowExecutionsRequest)}
   *
   * @param query Temporal Visibility Query, for syntax see <a
   *     href="https://docs.temporal.io/visibility#list-filter">Visibility docs</a>
   * @return sequential stream that performs remote pagination under the hood
   */
  Stream<WorkflowExecutionMetadata> listExecutions(@Nullable String query);

  /**
   * Streams history events for a workflow execution for the provided {@code workflowId}.
   *
   * @param workflowId Workflow Id of the workflow to export the history for
   * @return stream of history events of the workflow with the specified Workflow Id.
   * @see #streamHistory(String, String) to get a history of a specific run.
   * @see #fetchHistory(String) for a user-friendly eager version of this method
   */
  Stream<HistoryEvent> streamHistory(@Nonnull String workflowId);

  /**
   * Streams history events for a workflow execution for the provided {@code workflowId} and {@code
   * runId}.
   *
   * @param workflowId Workflow Id of the workflow to export the history for
   * @param runId Fixed Run Id of the workflow to export the history for. If not provided, the
   *     latest run will be used. Optional, can be null.
   * @return stream of history events of the specified run of the workflow execution.
   * @see #streamHistory(String) to get a history of workflow excution by workflowId without
   *     providing a specific run.
   * @see #fetchHistory(String, String) for a user-friendly eagert version of this method
   */
  Stream<HistoryEvent> streamHistory(@Nonnull String workflowId, @Nullable String runId);

  /**
   * Downloads workflow execution history for the provided {@code workflowId}.
   *
   * @param workflowId Workflow Id of the workflow to export the history for
   * @return execution history of the workflow with the specified Workflow Id.
   * @see #fetchHistory(String, String) to get a history of a specific run.
   * @see #streamHistory(String) for a lazy memory-efficient version of this method
   */
  WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId);

  /**
   * Downloads workflow execution history for the provided {@code workflowId} and {@code runId}.
   *
   * @param workflowId Workflow Id of the workflow to export the history for
   * @param runId Fixed Run Id of the workflow to export the history for. If not provided, the
   *     latest run will be used. Optional, can be null.
   * @return execution history of the specified run of the workflow execution.
   * @see #fetchHistory(String) to get a history of workflow excution by workflowId without
   *     providing a specific run.
   * @see #streamHistory(String, String) for a lazy memory-efficient version of this method
   */
  WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId, @Nullable String runId);

  /**
   * Allows you to update the worker-build-id based version sets for a particular task queue. This
   * is used in conjunction with workers who specify their build id and thus opt into the feature.
   *
   * @param taskQueue The task queue to update the version set(s) of.
   * @param operation The operation to perform. See {@link BuildIdOperation} for more.
   * @throws WorkflowServiceException for any failures including networking and service availability
   *     issues.
   */
  @Experimental
  void updateWorkerBuildIdCompatability(
      @Nonnull String taskQueue, @Nonnull BuildIdOperation operation);

  /**
   * Returns the worker-build-id based version sets for a particular task queue.
   *
   * @param taskQueue The task queue to fetch the version set(s) of.
   * @return The version set(s) for the task queue.
   * @throws WorkflowServiceException for any failures including networking and service availability
   *     issues.
   */
  @Experimental
  WorkerBuildIdVersionSets getWorkerBuildIdCompatability(@Nonnull String taskQueue);

  /**
   * Determine if some Build IDs for certain Task Queues could have tasks dispatched to them.
   *
   * @param buildIds The Build IDs to query the reachability of. At least one must be specified.
   * @param taskQueues Task Queues to restrict the query to. If not specified, all Task Queues will
   *     be searched. When requesting a large number of task queues or all task queues associated
   *     with the given Build IDs in a namespace, all Task Queues will be listed in the response but
   *     some of them may not contain reachability information due to a server enforced limit.
   * @param reachability The kind of reachability this request is concerned with.
   * @return The reachability information.
   * @throws WorkflowServiceException for any failures including networking and service availability
   *     issues.
   */
  @Experimental
  WorkerTaskReachability getWorkerTaskReachability(
      @Nonnull Iterable<String> buildIds,
      @Nonnull Iterable<String> taskQueues,
      TaskReachability reachability);

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static WorkflowExecution start(Functions.Proc workflow) {
    return WorkflowClientInternalImpl.start(workflow);
  }

  /**
   * Executes one argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1> WorkflowExecution start(Functions.Proc1<A1> workflow, A1 arg1) {
    return WorkflowClientInternalImpl.start(workflow, arg1);
  }

  /**
   * Executes two argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2> WorkflowExecution start(Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2);
  }

  /**
   * Executes three argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3> WorkflowExecution start(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes four argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4> WorkflowExecution start(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes five argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 fifth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5> WorkflowExecution start(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes six argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6> WorkflowExecution start(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <R> WorkflowExecution start(Functions.Func<R> workflow) {
    return WorkflowClientInternalImpl.start(workflow);
  }

  /**
   * Executes one argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, R> WorkflowExecution start(Functions.Func1<A1, R> workflow, A1 arg1) {
    return WorkflowClientInternalImpl.start(workflow, arg1);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, R> WorkflowExecution start(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2);
  }

  /**
   * Executes three argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, R> WorkflowExecution start(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes four argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, R> WorkflowExecution start(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes five argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, R> WorkflowExecution start(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes six argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6, R> WorkflowExecution start(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternalImpl.start(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static WorkflowExecution startWithOperation(
      Functions.Proc workflow, @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(workflow);
  }

  /**
   * Executes one argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1> WorkflowExecution startWithOperation(
      Functions.Proc1<A1> workflow,
      A1 arg1,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1));
  }

  /**
   * Executes two argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2> WorkflowExecution startWithOperation(
      Functions.Proc2<A1, A2> workflow,
      A1 arg1,
      A2 arg2,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2));
  }

  /**
   * Executes three argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3> WorkflowExecution startWithOperation(
      Functions.Proc3<A1, A2, A3> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3));
  }

  /**
   * Executes four argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4> WorkflowExecution startWithOperation(
      Functions.Proc4<A1, A2, A3, A4> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  /**
   * Executes five argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 fifth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5> WorkflowExecution startWithOperation(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Executes six argument workflow with void return type together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6> WorkflowExecution startWithOperation(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  /**
   * Executes zero argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <R> WorkflowExecution startWithOperation(
      Functions.Func<R> workflow, @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(
        () -> {
          workflow.apply();
        });
  }

  /**
   * Executes one argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, R> WorkflowExecution startWithOperation(
      Functions.Func1<A1, R> workflow,
      A1 arg1,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1));
  }

  /**
   * Executes two argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, R> WorkflowExecution startWithOperation(
      Functions.Func2<A1, A2, R> workflow,
      A1 arg1,
      A2 arg2,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2));
  }

  /**
   * Executes three argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, R> WorkflowExecution startWithOperation(
      Functions.Func3<A1, A2, A3, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3));
  }

  /**
   * Executes four argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, R> WorkflowExecution startWithOperation(
      Functions.Func4<A1, A2, A3, A4, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  /**
   * Executes five argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, R> WorkflowExecution startWithOperation(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Executes six argument workflow together with an additional operation.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @param additionalOperation to run at the beginning of the workflow (e.g. {@link
   *     WorkflowStartOperationUpdate} for Update-With-Start)
   * @return WorkflowExecution that contains WorkflowId and RunId of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6, R> WorkflowExecution startWithOperation(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6,
      @Nonnull StartWorkflowAdditionalOperation additionalOperation) {
    return additionalOperation.invoke(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static CompletableFuture<Void> execute(Proc workflow) {
    return WorkflowClientInternalImpl.execute(workflow);
  }

  /**
   * Executes one argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1> CompletableFuture<Void> execute(Proc1<A1> workflow, A1 arg1) {
    return WorkflowClientInternalImpl.execute(workflow, arg1);
  }

  /**
   * Executes two argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1, A2> CompletableFuture<Void> execute(Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2);
  }

  /**
   * Executes three argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1, A2, A3> CompletableFuture<Void> execute(
      Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes four argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1, A2, A3, A4> CompletableFuture<Void> execute(
      Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes five argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 fifth workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1, A2, A3, A4, A5> CompletableFuture<Void> execute(
      Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes six argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static <A1, A2, A3, A4, A5, A6> CompletableFuture<Void> execute(
      Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return future that contains workflow result or failure
   */
  static <R> CompletableFuture<R> execute(Func<R> workflow) {
    return WorkflowClientInternalImpl.execute(workflow);
  }

  /**
   * Executes one argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @return future that contains workflow result or failure
   */
  static <A1, R> CompletableFuture<R> execute(Func1<A1, R> workflow, A1 arg1) {
    return WorkflowClientInternalImpl.execute(workflow, arg1);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return future that contains workflow result or failure
   */
  static <A1, A2, R> CompletableFuture<R> execute(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2);
  }

  /**
   * Executes three argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return future that contains workflow result or failure
   */
  static <A1, A2, A3, R> CompletableFuture<R> execute(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes four argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @return future that contains workflow result or failure
   */
  static <A1, A2, A3, A4, R> CompletableFuture<R> execute(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes five argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @return future that contains workflow result or failure
   */
  static <A1, A2, A3, A4, A5, R> CompletableFuture<R> execute(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes six argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return future that contains workflow result or failure
   */
  static <A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> execute(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternalImpl.execute(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * For SDK Internal usage only. This method should <b>not</b> be used by users. If implementing a
   * proxy or an adapter over a {@link WorkflowClient} provided by the SDK, users should pass an
   * object returned by this method as-is.
   */
  Object getInternal();
}
