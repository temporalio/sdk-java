/*
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

package com.uber.cadence.client;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.Activity;
import com.uber.cadence.internal.sync.WorkflowClientInternal;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import com.uber.cadence.workflow.Functions.Proc;
import com.uber.cadence.workflow.Functions.Proc1;
import com.uber.cadence.workflow.Functions.Proc2;
import com.uber.cadence.workflow.Functions.Proc3;
import com.uber.cadence.workflow.Functions.Proc4;
import com.uber.cadence.workflow.Functions.Proc5;
import com.uber.cadence.workflow.Functions.Proc6;
import java.util.concurrent.CompletableFuture;

/**
 * Client to Cadence service used to start and query workflows by external processes. Also can be
 * used to create instances of {@link ActivityCompletionClient} to complete activities
 * asynchronously. Do not create this object for each request, keep it for the duration of the
 * process.
 *
 * <p>Use {@link #newInstance(String)} method to create an instance. Example usage:
 *
 * <pre>
 * // Create cadence client using cadence service host and port.
 * WorkflowClient client = WorkflowClient.newInstance(host, port, domain);
 *
 * // Create client side stub to the workflow execution.
 * HelloWorldWorkflow workflow = client.newWorkflowStub(HelloWorldWorkflow.class);
 *
 * // Start Workflow Execution
 * WorkflowExecution started = WorkflowClient.start(workflow::helloWorld, "User");
 *
 * // started.getWorkflowId() should match the one in the options: "MyHelloWorld1"
 * System.out.println("Started helloWorld workflow with workflowId=\"" + started.getWorkflowId()
 *                     + "\" and runId=\"" + started.getRunId() + "\"");
 * </pre>
 *
 * Synchronously waiting for workflow completion:
 *
 * <pre>
 * String result = workflow.helloWorld("User");
 * </pre>
 *
 * Asynchronously waiting for workflow completion:
 *
 * <pre>
 * CompletableFuture&lt;String&gt; result = WorkflowClient.execute(workflow::helloWorld, "User");
 * </pre>
 */
public interface WorkflowClient {

  /** Use this constant as a query type to get a workflow stack trace. */
  String QUERY_TYPE_STACK_TRCE = "__stack_trace";

  /**
   * Creates worker that connects to the local instance of the Cadence Service that listens on a
   * default port (7933).
   *
   * @param domain domain that worker uses to poll.
   */
  static WorkflowClient newInstance(String domain) {
    return new WorkflowClientInternal(
        new WorkflowServiceTChannel(), domain, new WorkflowClientOptions.Builder().build());
  }

  /**
   * Creates worker that connects to the local instance of the Cadence Service that listens on a
   * default port (7933).
   *
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter}er override) for
   *     configuring client.
   */
  static WorkflowClient newInstance(String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(new WorkflowServiceTChannel(), domain, options);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param host of the Cadence Service endpoint
   * @param port of the Cadence Service endpoint
   * @param domain domain that worker uses to poll.
   */
  static WorkflowClient newInstance(String host, int port, String domain) {
    return new WorkflowClientInternal(
        new WorkflowServiceTChannel(host, port),
        domain,
        new WorkflowClientOptions.Builder().build());
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param host of the Cadence Service endpoint
   * @param port of the Cadence Service endpoint
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter}er override) for
   *     configuring client.
   */
  static WorkflowClient newInstance(
      String host, int port, String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(new WorkflowServiceTChannel(host, port), domain, options);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   */
  static WorkflowClient newInstance(IWorkflowService service, String domain) {
    return new WorkflowClientInternal(service, domain, null);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter}er override) for
   *     configuring client.
   */
  static WorkflowClient newInstance(
      IWorkflowService service, String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(service, domain, options);
  }

  /**
   * Creates workflow client stub that can be used to start a single workflow execution. The first
   * call must be to a method annotated with @WorkflowMethod. After workflow is started it can be
   * also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
   * stub should be created for each new one.
   *
   * @param workflowInterface interface that given workflow implements
   * @return Stub that implements workflowInterface and can be used to start workflow and later to
   *     signal or query it.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface);

  /**
   * Creates workflow client stub that can be used to start a single workflow execution. The first
   * call must be to a method annotated with @WorkflowMethod. After workflow is started it can be
   * also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
   * stub should be created for each new one.
   *
   * @param workflowInterface interface that given workflow implements
   * @param options options used to start a workflow through returned stub
   * @return Stub that implements workflowInterface and can be used to start workflow and later to
   *     signal or query it.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options);

  /**
   * Creates workflow client stub for a known execution. Use it to send signals or queries to a
   * running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param workflowInterface interface that given workflow implements
   * @param execution workflow id and optional run id for execution
   * @return Stub that implements workflowInterface and can be used to signal or query it.
   */
  <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowExecution execution);

  /**
   * Creates workflow untyped client stub that can be used to start a single workflow execution.
   * After workflow is started it can be also used to send signals or queries to it. IMPORTANT! Stub
   * is per workflow instance. So new stub should be created for each new one.
   *
   * @param workflowType name of the workflow type
   * @param options options used to start a workflow through returned stub
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  UntypedWorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions options);

  /**
   * Creates workflow untyped client stub for a known execution. Use it to send signals or queries
   * to a running workflow. Do not call methods annotated with @WorkflowMethod.
   *
   * @param execution workflow id and optional run id for execution
   * @return Stub that can be used to start workflow and later to signal or query it.
   */
  UntypedWorkflowStub newUntypedWorkflowStub(WorkflowExecution execution);

  /**
   * Creates new {@link ActivityCompletionClient} that can be used to complete activities
   * asynchronously. Only relevant for activity implementations that called {@link
   * Activity#doNotCompleteOnReturn()}.
   *
   * <p>TODO: Activity completion options with retries and timeouts. <
   */
  ActivityCompletionClient newActivityCompletionClient();

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static WorkflowExecution start(Functions.Proc workflow) {
    return WorkflowClientInternal.start(workflow);
  }

  /**
   * Executes one argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1> WorkflowExecution start(Functions.Proc1<A1> workflow, A1 arg1) {
    return WorkflowClientInternal.start(workflow, arg1);
  }

  /**
   * Executes two argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2> WorkflowExecution start(Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternal.start(workflow, arg1, arg2);
  }

  /**
   * Executes three argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3> WorkflowExecution start(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3);
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
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4> WorkflowExecution start(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 fifth workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4, A5> WorkflowExecution start(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6> WorkflowExecution start(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <R> WorkflowExecution start(Functions.Func<R> workflow) {
    return WorkflowClientInternal.start(workflow);
  }

  /**
   * Executes one argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, R> WorkflowExecution start(Functions.Func1<A1, R> workflow, A1 arg1) {
    return WorkflowClientInternal.start(workflow, arg1);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, R> WorkflowExecution start(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return WorkflowClientInternal.start(workflow, arg1, arg2);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, R> WorkflowExecution start(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4, R> WorkflowExecution start(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4, A5, R> WorkflowExecution start(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes two argument workflow asynchronously.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow function parameter
   * @param arg3 third workflow function parameter
   * @param arg4 fourth workflow function parameter
   * @param arg5 sixth workflow function parameter
   * @param arg6 sixth workflow function parameter
   * @return WorkflowExecution that contains WorkflowID and RunID of the started workflow.
   */
  static <A1, A2, A3, A4, A5, A6, R> WorkflowExecution start(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return WorkflowClientInternal.start(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return future becomes ready upon workflow completion with null value or failure
   */
  static CompletableFuture<Void> execute(Proc workflow) {
    return WorkflowClientInternal.execute(workflow);
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
    return WorkflowClientInternal.execute(workflow, arg1);
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2);
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3);
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes zero argument workflow with void return type
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes zero argument workflow with void return type
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Executes zero argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     #newWorkflowStub(Class, WorkflowOptions)}.
   * @return future that contains workflow result or failure
   */
  static <R> CompletableFuture<R> execute(Func<R> workflow) {
    return WorkflowClientInternal.execute(workflow);
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
    return WorkflowClientInternal.execute(workflow, arg1);
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2);
  }

  /**
   * Executes two argument workflow asynchronously.
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3);
  }

  /**
   * Executes two argument workflow asynchronously.
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4);
  }

  /**
   * Executes two argument workflow asynchronously.
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Executes two argument workflow asynchronously.
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
    return WorkflowClientInternal.execute(workflow, arg1, arg2, arg3, arg4, arg5, arg6);
  }
}
