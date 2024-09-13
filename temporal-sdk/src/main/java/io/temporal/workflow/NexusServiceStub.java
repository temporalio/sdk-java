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

package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.lang.reflect.Type;

/**
 * NexusServiceStub is used to start operations on a Nexus service without referencing an interface
 * it implements. This is useful to call operations when their type is not known at compile time or
 * to execute operations implemented in other languages. Created through {@link
 * Workflow#newNexusServiceStub(Class)}.
 */
@Experimental
public interface NexusServiceStub {

  /**
   * Executes an operation by its type name and arguments. Blocks until the operation completion.
   *
   * @param operationName name of the operation type to execute.
   * @param resultClass the expected return type of the operation.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return an operation result.
   */
  <R> R execute(String operationName, Class<R> resultClass, Object arg);

  /**
   * Executes an operation by its type name and arguments. Blocks until the operation completion.
   *
   * @param operationName name of the operation type to execute.
   * @param resultClass the expected return type of the operation.
   * @param resultType the expected return type of the nexus operation. Differs from resultClass for
   *     generic types.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return an operation result.
   */
  <R> R execute(String operationName, Class<R> resultClass, Type resultType, Object arg);

  /**
   * Executes an operation asynchronously by its type name and arguments.
   *
   * @param operationName name of an operation type to execute.
   * @param resultClass the expected return type of the operation. Use Void.class for operations
   *     that return void type.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return Promise to the operation result.
   */
  <R> Promise<R> executeAsync(String operationName, Class<R> resultClass, Object arg);

  /**
   * Executes an operation asynchronously by its type name and arguments.
   *
   * @param operationName name of an operation type to execute.
   * @param resultClass the expected return type of the operation. Use Void.class for operations
   *     that return void type.
   * @param resultType the expected return type of the nexus operation. Differs from resultClass for
   *     generic types.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return Promise to the operation result.
   */
  <R> Promise<R> executeAsync(
      String operationName, Class<R> resultClass, Type resultType, Object arg);

  /**
   * Request to start an operation by its type name and arguments
   *
   * @param operationName name of an operation type to execute.
   * @param resultClass the expected return type of the operation. Use Void.class for operations
   *     that return void type.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return A handle that can be used to wait for the operation to start or wait for it to finish
   */
  <R> NexusOperationHandle<R> start(String operationName, Class<R> resultClass, Object arg);

  /**
   * Request to start an operation by its type name and arguments
   *
   * @param operationName name of an operation type to execute.
   * @param resultClass the expected return type of the operation. Use Void.class for operations
   *     that return void type.
   * @param resultType the expected return type of the nexus operation. Differs from resultClass for
   *     generic types.
   * @param arg argument of the operation.
   * @param <R> return type.
   * @return A handle that can be used to wait for the operation to start or wait for it to finish
   */
  <R> NexusOperationHandle<R> start(
      String operationName, Class<R> resultClass, Type resultType, Object arg);
}
