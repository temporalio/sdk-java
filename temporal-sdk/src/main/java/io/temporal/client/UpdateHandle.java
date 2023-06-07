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

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.Experimental;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * UpdateHandle is a handle to an update workflow execution request that can be used to get the
 * status of that update request.
 */
@Experimental
public interface UpdateHandle<T> {
  /**
   * Gets the workflow execution this update request was sent to.
   *
   * @return the workflow execution this update was sent to.
   */
  WorkflowExecution getExecution();

  /**
   * Gets the unique ID of this update.
   *
   * @return the updates ID.
   */
  String getId();

  /**
   * Returns a {@link CompletableFuture} with the update workflow execution request result,
   * potentially waiting for the update to complete.
   *
   * @return future completed with the result of the update or an exception
   */
  CompletableFuture<T> getResultAsync();

  /**
   * Returns a {@link CompletableFuture} with the update workflow execution request result,
   * potentially waiting for the update to complete.
   *
   * @param timeout maximum time to wait and perform the background long polling
   * @param unit unit of timeout
   * @return future completed with the result of the update or an exception
   */
  CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit);
}
