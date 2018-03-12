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
import java.util.concurrent.CancellationException;

/**
 * Used to complete asynchronously activities that called {@link Activity#doNotCompleteOnReturn()}.
 *
 * <p>Use {@link WorkflowClient#newActivityCompletionClient()} to create an instance. TODO: Throw
 * relevant exceptions like EntityNotExists, etc.
 */
public interface ActivityCompletionClient {

  <R> void complete(byte[] taskToken, R result) throws ActivityCompletionException;

  <R> void complete(WorkflowExecution execution, String activityId, R result)
      throws ActivityCompletionException;

  // TODO: Exception serialization/deserialization or wrapping.
  void completeExceptionally(byte[] taskToken, Exception result) throws ActivityCompletionException;

  void completeExceptionally(WorkflowExecution execution, String activityId, Exception result)
      throws ActivityCompletionException;

  <V> void reportCancellation(byte[] taskToken, V details) throws ActivityCompletionException;

  <V> void reportCancellation(WorkflowExecution execution, String activityId, V details)
      throws ActivityCompletionException;

  <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException;

  /**
   * Warning: heartbeating by ids is not implemented yet.
   *
   * @throws CancellationException if activity is cancelled.
   */
  <V> void heartbeat(WorkflowExecution execution, String activityId, V details)
      throws ActivityCompletionException;
}
