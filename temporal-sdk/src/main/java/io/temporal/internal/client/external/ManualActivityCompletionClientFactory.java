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

import com.uber.m3.tally.Scope;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ManualActivityCompletionClientFactory {

  /**
   * Create a {@link ManualActivityCompletionClientFactory} that emits simple {@link
   * ManualActivityCompletionClientImpl} implementations
   */
  static ManualActivityCompletionClientFactory newFactory(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull DataConverter dataConverter) {
    return new ManualActivityCompletionClientFactoryImpl(
        service, namespace, identity, dataConverter);
  }

  ManualActivityCompletionClient getClient(@Nonnull byte[] taskToken, @Nonnull Scope metricsScope);

  ManualActivityCompletionClient getClient(
      @Nonnull byte[] taskToken,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext);

  ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope);

  ManualActivityCompletionClient getClient(
      @Nonnull WorkflowExecution execution,
      @Nonnull String activityId,
      @Nonnull Scope metricsScope,
      @Nullable ActivitySerializationContext activitySerializationContext);
}
