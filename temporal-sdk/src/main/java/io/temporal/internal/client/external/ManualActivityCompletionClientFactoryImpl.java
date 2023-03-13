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

package io.temporal.internal.client.external;

import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class ManualActivityCompletionClientFactoryImpl implements ManualActivityCompletionClientFactory {
  private final WorkflowServiceStubs service;
  private final DataConverter dataConverter;
  private final String namespace;
  private final String identity;

  ManualActivityCompletionClientFactoryImpl(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull DataConverter dataConverter) {
    this.service = Objects.requireNonNull(service);
    this.namespace = Objects.requireNonNull(namespace);
    this.identity = Objects.requireNonNull(identity);
    this.dataConverter = Objects.requireNonNull(dataConverter);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken, @Nonnull Scope metricsScope) {
    return getClient(taskToken, metricsScope, null);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext) {
    Preconditions.checkNotNull(metricsScope, "metricsScope");
    Preconditions.checkNotNull(taskToken, "taskToken");
    Preconditions.checkArgument(taskToken.length > 0, "empty taskToken");
    return new ManualActivityCompletionClientImpl(
        service,
        namespace,
        identity,
        dataConverter,
        metricsScope,
        taskToken,
        null,
        null,
        activitySerializationContext);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope) {
    return getClient(execution, activityId, metricsScope, null);
  }

  @Override
  public ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext) {
    Preconditions.checkNotNull(metricsScope, "metricsScope");
    Preconditions.checkNotNull(execution, "execution");
    Preconditions.checkNotNull(activityId, "activityId");
    return new ManualActivityCompletionClientImpl(
        service,
        namespace,
        identity,
        dataConverter,
        metricsScope,
        null,
        execution,
        activityId,
        activitySerializationContext);
  }
}
