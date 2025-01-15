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

package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

public class ActivityExecutionContextFactoryImpl implements ActivityExecutionContextFactory {
  private final WorkflowClient client;
  private final String identity;
  private final String namespace;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;
  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;
  private final ManualActivityCompletionClientFactory manualCompletionClientFactory;

  public ActivityExecutionContextFactoryImpl(
      WorkflowClient client,
      String identity,
      String namespace,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.client = Objects.requireNonNull(client);
    this.identity = identity;
    this.namespace = Objects.requireNonNull(namespace);
    this.maxHeartbeatThrottleInterval = Objects.requireNonNull(maxHeartbeatThrottleInterval);
    this.defaultHeartbeatThrottleInterval =
        Objects.requireNonNull(defaultHeartbeatThrottleInterval);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
    this.manualCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            client.getWorkflowServiceStubs(), namespace, identity, dataConverter);
  }

  @Override
  public InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Scope metricsScope) {
    return new ActivityExecutionContextImpl(
        client,
        namespace,
        info,
        dataConverter,
        heartbeatExecutor,
        manualCompletionClientFactory,
        info.getCompletionHandle(),
        metricsScope,
        identity,
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval);
  }
}
