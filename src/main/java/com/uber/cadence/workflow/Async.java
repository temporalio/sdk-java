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

package com.uber.cadence.workflow;

import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.sync.AsyncInternal;

/** Supports invoking lambdas and activity and child workflow references asynchronously. */
public final class Async {

  /**
   * Invokes zero argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @return promise that contains function result or failure
   */
  public static <R> Promise<R> function(Functions.Func<R> function) {
    return AsyncInternal.function(function);
  }

  /**
   * Invokes one argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @return promise that contains function result or failure
   */
  public static <A1, R> Promise<R> function(Functions.Func1<A1, R> function, A1 arg1) {
    return AsyncInternal.function(function, arg1);
  }

  /**
   * Invokes two argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, R> Promise<R> function(
      Functions.Func2<A1, A2, R> function, A1 arg1, A2 arg2) {
    return AsyncInternal.function(function, arg1, arg2);
  }

  /**
   * Invokes three argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, R> Promise<R> function(
      Functions.Func3<A1, A2, A3, R> function, A1 arg1, A2 arg2, A3 arg3) {
    return AsyncInternal.function(function, arg1, arg2, arg3);
  }

  /**
   * Invokes four argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, R> Promise<R> function(
      Functions.Func4<A1, A2, A3, A4, R> function, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4);
  }

  /**
   * Invokes five argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @param arg5 fifth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, A5, R> Promise<R> function(
      Functions.Func5<A1, A2, A3, A4, A5, R> function,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Invokes six argument function asynchronously.
   *
   * @param function Function to execute asynchronously
   * @param arg1 first function argument
   * @param arg2 second function argument
   * @param arg3 third function argument
   * @param arg4 forth function argument
   * @param arg5 fifth function argument
   * @param arg6 sixth function argument
   * @return Promise that contains function result or failure
   */
  public static <A1, A2, A3, A4, A5, A6, R> Promise<R> function(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> function,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return AsyncInternal.function(function, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Invokes zero argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @return Promise that contains procedure result or failure
   */
  public static Promise<Void> procedure(Functions.Proc procedure) {
    return AsyncInternal.procedure(procedure);
  }

  /**
   * Invokes one argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1> Promise<Void> procedure(Functions.Proc1<A1> procedure, A1 arg1) {
    return AsyncInternal.procedure(procedure, arg1);
  }

  /**
   * Invokes two argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2> Promise<Void> procedure(
      Functions.Proc2<A1, A2> procedure, A1 arg1, A2 arg2) {
    return AsyncInternal.procedure(procedure, arg1, arg2);
  }

  /**
   * Invokes three argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3> Promise<Void> procedure(
      Functions.Proc3<A1, A2, A3> procedure, A1 arg1, A2 arg2, A3 arg3) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3);
  }

  /**
   * Invokes four argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4> Promise<Void> procedure(
      Functions.Proc4<A1, A2, A3, A4> procedure, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4);
  }

  /**
   * Invokes five argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @param arg5 fifth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4, A5> Promise<Void> procedure(
      Functions.Proc5<A1, A2, A3, A4, A5> procedure, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4, arg5);
  }

  /**
   * Invokes six argument procedure asynchronously.
   *
   * @param procedure Procedure to execute asynchronously
   * @param arg1 first procedure argument
   * @param arg2 second procedure argument
   * @param arg3 third procedure argument
   * @param arg4 forth procedure argument
   * @param arg5 fifth procedure argument
   * @param arg6 sixth procedure argument
   * @return Promise that contains procedure result or failure
   */
  public static <A1, A2, A3, A4, A5, A6> Promise<Void> procedure(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> procedure,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return AsyncInternal.procedure(procedure, arg1, arg2, arg3, arg4, arg5, arg6);
  }

  /**
   * Invokes function retrying in case of failures according to retry options. Asynchronous variant.
   * Use {@link Workflow#retry(RetryOptions, Functions.Func)} for synchronous functions.
   *
   * @param options retry options that specify retry policy
   * @param fn function to invoke and retry
   * @return result of the function or the last failure.
   */
  public static <R> Promise<R> retry(RetryOptions options, Functions.Func<Promise<R>> fn) {
    return AsyncInternal.retry(options, fn);
  }

  /** Prohibits instantiation. */
  private Async() {}
}
