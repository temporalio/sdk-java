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

import com.uber.cadence.workflow.Functions;

/** Used to accumulate multiple operations */
public interface BatchRequest {

  /**
   * Executes zero argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  void add(Functions.Proc request);

  /**
   * Executes one argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   */
  <A1> void add(Functions.Proc1<A1> request, A1 arg1);

  /**
   * Executes two argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   */
  <A1, A2> void add(Functions.Proc2<A1, A2> request, A1 arg1, A2 arg2);

  /**
   * Executes three argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   */
  <A1, A2, A3> void add(Functions.Proc3<A1, A2, A3> request, A1 arg1, A2 arg2, A3 arg3);

  /**
   * Executes four argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   */
  <A1, A2, A3, A4> void add(
      Functions.Proc4<A1, A2, A3, A4> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4);

  /**
   * Executes five argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 fifth request function parameter
   */
  <A1, A2, A3, A4, A5> void add(
      Functions.Proc5<A1, A2, A3, A4, A5> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5);

  /**
   * Executes six argument request with void return type
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 sixth request function parameter
   * @param arg6 sixth request function parameter
   */
  <A1, A2, A3, A4, A5, A6> void add(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);

  /**
   * Executes zero argument request.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  void add(Functions.Func<?> request);

  /**
   * Executes one argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request argument
   */
  <A1> void add(Functions.Func1<A1, ?> request, A1 arg1);

  /**
   * Executes two argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   */
  <A1, A2> void add(Functions.Func2<A1, A2, ?> request, A1 arg1, A2 arg2);

  /**
   * Executes three argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   */
  <A1, A2, A3> void add(Functions.Func3<A1, A2, A3, ?> request, A1 arg1, A2 arg2, A3 arg3);

  /**
   * Executes four argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   */
  <A1, A2, A3, A4> void add(
      Functions.Func4<A1, A2, A3, A4, ?> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4);

  /**
   * Executes five argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request function parameter
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 sixth request function parameter
   */
  <A1, A2, A3, A4, A5> void add(
      Functions.Func5<A1, A2, A3, A4, A5, ?> request, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5);

  /**
   * Executes six argument request asynchronously.
   *
   * @param request The only supported value is method reference to a proxy created through {@link
   *     WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first request argument
   * @param arg2 second request function parameter
   * @param arg3 third request function parameter
   * @param arg4 fourth request function parameter
   * @param arg5 sixth request function parameter
   * @param arg6 sixth request function parameter
   */
  <A1, A2, A3, A4, A5, A6> void add(
      Functions.Func6<A1, A2, A3, A4, A5, A6, ?> request,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6);
}
