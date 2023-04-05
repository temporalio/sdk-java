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

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.workflow.Functions;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ActivityCompletionClientImpl implements ActivityCompletionClient {

  private final ManualActivityCompletionClientFactory factory;
  private final Functions.Proc completionHandle;
  private final Scope metricsScope;
  private final @Nullable ActivitySerializationContext serializationContext;

  ActivityCompletionClientImpl(
      ManualActivityCompletionClientFactory manualActivityCompletionClientFactory,
      Functions.Proc completionHandle,
      Scope metricsScope,
      @Nullable ActivitySerializationContext serializationContext) {
    this.factory = manualActivityCompletionClientFactory;
    this.completionHandle = completionHandle;
    this.metricsScope = metricsScope;
    this.serializationContext = serializationContext;
  }

  @Override
  public <R> void complete(byte[] taskToken, R result) {
    try {
      factory.getClient(taskToken, metricsScope, serializationContext).complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <R> void complete(String workflowId, Optional<String> runId, String activityId, R result) {
    try {
      factory
          .getClient(toExecution(workflowId, runId), activityId, metricsScope, serializationContext)
          .complete(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void completeExceptionally(byte[] taskToken, Exception result) {
    try {
      factory.getClient(taskToken, metricsScope, serializationContext).fail(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public void completeExceptionally(
      String workflowId, Optional<String> runId, String activityId, Exception result) {
    try {
      factory
          .getClient(toExecution(workflowId, runId), activityId, metricsScope, serializationContext)
          .fail(result);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void reportCancellation(byte[] taskToken, V details) {
    try {
      factory.getClient(taskToken, metricsScope, serializationContext).reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void reportCancellation(
      String workflowId, Optional<String> runId, String activityId, V details) {
    try {
      factory
          .getClient(toExecution(workflowId, runId), activityId, metricsScope, serializationContext)
          .reportCancellation(details);
    } finally {
      completionHandle.apply();
    }
  }

  @Override
  public <V> void heartbeat(byte[] taskToken, V details) throws ActivityCompletionException {
    factory.getClient(taskToken, metricsScope).recordHeartbeat(details);
  }

  @Override
  public <V> void heartbeat(String workflowId, Optional<String> runId, String activityId, V details)
      throws ActivityCompletionException {
    factory
        .getClient(toExecution(workflowId, runId), activityId, metricsScope, serializationContext)
        .recordHeartbeat(details);
  }

  @Nonnull
  @Override
  public ActivityCompletionClient withContext(@Nonnull ActivitySerializationContext context) {
    return new ActivityCompletionClientImpl(factory, completionHandle, metricsScope, context);
  }

  private static WorkflowExecution toExecution(String workflowId, Optional<String> runId) {
    return WorkflowExecution.newBuilder()
        .setWorkflowId(workflowId)
        .setRunId(runId.orElse(""))
        .build();
  }
}
