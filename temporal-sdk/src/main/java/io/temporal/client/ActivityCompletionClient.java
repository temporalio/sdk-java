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

import io.temporal.activity.ActivityExecutionContext;
import java.util.Optional;

/**
 * Used to complete asynchronously activities that called {@link
 * ActivityExecutionContext#doNotCompleteOnReturn()}.
 *
 * <p>Use {@link WorkflowClient#newActivityCompletionClient()} to create an instance.
 */
public interface ActivityCompletionClient {

  <R> void complete(byte[] taskToken, R result) throws ActivityCompletionException;

  <R> void complete(String workflowId, Optional<String> runId, String activityId, R result)
      throws ActivityCompletionException;

  void completeExceptionally(byte[] taskToken, Exception result) throws ActivityCompletionException;

  void completeExceptionally(
      String workflowId, Optional<String> runId, String activityId, Exception result)
      throws ActivityCompletionException;

  <V> void reportCancellation(byte[] taskToken, V details) throws ActivityCompletionException;

  <V> void reportCancellation(
      String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException;

  <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException;

  /**
   * @throws ActivityCompletionException if activity should stop executing
   */
  <V> void heartbeat(String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException;
}
