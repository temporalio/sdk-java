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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.internal.external.ManualActivityCompletionClientFactory;
import io.temporal.workflow.Functions;
import java.util.Optional;

class ActivityCompletionClientImpl implements ActivityCompletionClient {

  private final ManualActivityCompletionClientFactory factory;
  private final Functions.Proc completionHandle;

  public ActivityCompletionClientImpl(
      ManualActivityCompletionClientFactory manualActivityCompletionClientFactory,
      Functions.Proc completionHandle) {
    this.factory = manualActivityCompletionClientFactory;
    this.completionHandle = completionHandle;
  }

  @Override
  public <R> void complete(byte[] taskToken, R result) {
    try {
      factory.getClient(taskToken).complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <R> void complete(String workflowId, Optional<String> runId, String activityId, R result) {
    try {
      factory.getClient(toExecution(workflowId, runId), activityId).complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void completeExceptionally(byte[] taskToken, Exception result) {
    try {
      factory.getClient(taskToken).fail(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void completeExceptionally(
      String workflowId, Optional<String> runId, String activityId, Exception result) {
    try {
      factory.getClient(toExecution(workflowId, runId), activityId).fail(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void reportCancellation(byte[] taskToken, V details) {
    try {
      factory.getClient(taskToken).reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void reportCancellation(
      String workflowId, Optional<String> runId, String activityId, V details) {
    try {
      factory.getClient(toExecution(workflowId, runId), activityId).reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException {
    factory.getClient(taskToken).recordHeartbeat(details);
  }

  @Override
  public <V> void heartbeat(String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException {
    factory.getClient(toExecution(workflowId, runId), activityId).recordHeartbeat(details);
  }

  Functions.Proc getCompletionHandle() {
    return completionHandle;
  }

  private static WorkflowExecution toExecution(String workflowId, Optional<String> runId) {
    return WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .setRunId(runId.orElse(""))
        .build();
  }
}
