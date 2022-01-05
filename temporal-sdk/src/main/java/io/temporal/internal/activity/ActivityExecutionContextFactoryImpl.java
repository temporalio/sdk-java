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

package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.converter.DataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

public class ActivityExecutionContextFactoryImpl implements ActivityExecutionContextFactory {
  private final WorkflowServiceStubs service;
  private final String identity;
  private final String namespace;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;
  private final DataConverter dataConverter;
  private final ScheduledExecutorService heartbeatExecutor;

  public ActivityExecutionContextFactoryImpl(
      WorkflowServiceStubs service,
      String identity,
      String namespace,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.service = Objects.requireNonNull(service);
    this.identity = identity;
    this.namespace = Objects.requireNonNull(namespace);
    this.maxHeartbeatThrottleInterval = Objects.requireNonNull(maxHeartbeatThrottleInterval);
    this.defaultHeartbeatThrottleInterval =
        Objects.requireNonNull(defaultHeartbeatThrottleInterval);
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor);
  }

  @Override
  public ActivityExecutionContext createContext(ActivityInfoInternal info, Scope metricsScope) {
    return new ActivityExecutionContextImpl(
        service,
        namespace,
        info,
        dataConverter,
        heartbeatExecutor,
        info.getCompletionHandle(),
        metricsScope,
        identity,
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval);
  }
}
