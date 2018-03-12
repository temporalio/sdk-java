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

package com.uber.cadence.internal.sync;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.ActivityCompletionException;
import com.uber.cadence.internal.external.ManualActivityCompletionClientFactory;

class ActivityCompletionClientImpl implements ActivityCompletionClient {

  private final ManualActivityCompletionClientFactory factory;

  public ActivityCompletionClientImpl(
      ManualActivityCompletionClientFactory manualActivityCompletionClientFactory) {
    this.factory = manualActivityCompletionClientFactory;
  }

  @Override
  public <R> void complete(byte[] taskToken, R result) {
    factory.getClient(taskToken).complete(result);
  }

  @Override
  public <R> void complete(WorkflowExecution execution, String activityId, R result) {
    factory.getClient(execution, activityId).complete(result);
  }

  @Override
  public void completeExceptionally(byte[] taskToken, Exception result) {
    factory.getClient(taskToken).fail(result);
  }

  @Override
  public void completeExceptionally(
      WorkflowExecution execution, String activityId, Exception result) {
    factory.getClient(execution, activityId).fail(result);
  }

  @Override
  public <V> void reportCancellation(byte[] taskToken, V details) {
    factory.getClient(taskToken).reportCancellation(details);
  }

  @Override
  public <V> void reportCancellation(WorkflowExecution execution, String activityId, V details) {
    factory.getClient(execution, activityId).reportCancellation(details);
  }

  @Override
  public <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException {
    factory.getClient(taskToken).recordHeartbeat(details);
  }

  @Override
  public <V> void heartbeat(WorkflowExecution execution, String activityId, V details)
      throws ActivityCompletionException {
    factory.getClient(execution, activityId).recordHeartbeat(details);
  }
}
