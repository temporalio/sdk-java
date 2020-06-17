/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.client;

import java.util.Optional;

/**
 * Used to complete asynchronously activities that called {@link
 * io.temporal.internal.sync.ActivityExecutionContext#doNotCompleteOnReturn()}.
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

  /** @throws ActivityCompletionException if activity should stop executing */
  <V> void heartbeat(String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException;
}
