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

package io.temporal.nexus;

import io.temporal.client.*;
import io.temporal.workflow.Functions;

/** WorkflowHandle is a readonly representation of a workflow run backing a Nexus operation. */
public final class WorkflowHandle<R> {
  /**
   * Create a handle to a zero argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowHandle
   */
  public static WorkflowHandle<Void> fromWorkflowMethod(Functions.Proc workflow) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(workflow));
  }

  /**
   * Create a handle to a one argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @return WorkflowHandle
   */
  public static <A1> WorkflowHandle<Void> fromWorkflowMethod(
      Functions.Proc1<A1> workflow, A1 arg1) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(() -> workflow.apply(arg1)));
  }

  /**
   * Create a handle to a two argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @return WorkflowHandle
   */
  public static <A1, A2> WorkflowHandle<Void> fromWorkflowMethod(
      Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(() -> workflow.apply(arg1, arg2)));
  }

  /**
   * Create a handle to a two argument workflow with void return type
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @param arg2 second workflow argument
   * @param arg3 third workflow argument
   * @return WorkflowHandle
   */
  public static <A1, A2, A3> WorkflowHandle<Void> fromWorkflowMethod(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return new WorkflowHandle(
        new WorkflowMethodMethodInvoker(() -> workflow.apply(arg1, arg2, arg3)));
  }

  /**
   * Create a handle to a zero argument workflow
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @return WorkflowHandle
   */
  public static <R> WorkflowHandle<R> fromWorkflowMethod(Functions.Func<R> workflow) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(() -> workflow.apply()));
  }

  /**
   * Create a handle to a one argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow argument
   * @return WorkflowHandle
   */
  public static <A1, R> WorkflowHandle<R> fromWorkflowMethod(
      Functions.Func1<A1, R> workflow, A1 arg1) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(() -> workflow.apply(arg1)));
  }

  /**
   * Create a handle to a two argument workflow.
   *
   * @param workflow The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow function parameter
   * @param arg2 second workflow function parameter
   * @return WorkflowHandle
   */
  public static <A1, A2, R> WorkflowHandle<R> fromWorkflowMethod(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return new WorkflowHandle(new WorkflowMethodMethodInvoker(() -> workflow.apply(arg1, arg2)));
  }

  /**
   * Create a WorkflowHandle from an untyped workflow stub.
   *
   * @param stub The workflow stub to use
   * @param resultClass class of the workflow return value
   * @param args arguments to start the workflow
   * @return WorkflowHandle
   */
  static <R> WorkflowHandle<R> fromWorkflowStub(
      WorkflowStub stub, Class<R> resultClass, Object... args) {
    return new WorkflowHandle(new WorkflowStubHandleInvoker(stub, args));
  }

  final WorkflowHandleInvoker invoker;

  WorkflowHandleInvoker getInvoker() {
    return invoker;
  }

  /** Prohibits outside instantiation. */
  private WorkflowHandle(WorkflowHandleInvoker invoker) {
    this.invoker = invoker;
  }
}
