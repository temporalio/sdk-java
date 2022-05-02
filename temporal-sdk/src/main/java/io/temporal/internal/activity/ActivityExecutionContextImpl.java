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
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Base implementation of an {@link ActivityExecutionContext}.
 *
 * @author fateev, suskin
 * @see ActivityExecutionContext
 */
@ThreadSafe
class ActivityExecutionContextImpl implements ActivityExecutionContext {
  private final Lock lock = new ReentrantLock();
  private final ManualActivityCompletionClientFactory manualCompletionClientFactory;
  private final Functions.Proc completionHandle;
  private final HeartbeatContext heartbeatContext;

  private final Scope metricsScope;
  private final ActivityInfo info;
  private boolean useLocalManualCompletion;
  private boolean doNotCompleteOnReturn;

  /** Create an ActivityExecutionContextImpl with the given attributes. */
  ActivityExecutionContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      ManualActivityCompletionClientFactory manualCompletionClientFactory,
      Functions.Proc completionHandle,
      Scope metricsScope,
      String identity,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    this.metricsScope = metricsScope;
    this.info = info;
    this.completionHandle = completionHandle;
    this.manualCompletionClientFactory = manualCompletionClientFactory;
    this.heartbeatContext =
        new HeartbeatContextImpl(
            service,
            namespace,
            info,
            dataConverter,
            heartbeatExecutor,
            metricsScope,
            identity,
            maxHeartbeatThrottleInterval,
            defaultHeartbeatThrottleInterval);
  }

  /**
   * @see ActivityExecutionContext#heartbeat(Object)
   */
  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    heartbeatContext.heartbeat(details);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return getHeartbeatDetails(detailsClass, detailsClass);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    return heartbeatContext.getHeartbeatDetails(detailsClass, detailsGenericType);
  }

  @Override
  public byte[] getTaskToken() {
    return info.getTaskToken();
  }

  @Override
  public void doNotCompleteOnReturn() {
    lock.lock();
    try {
      doNotCompleteOnReturn = true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    lock.lock();
    try {
      return doNotCompleteOnReturn;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    lock.lock();
    try {
      return useLocalManualCompletion;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    lock.lock();
    try {
      doNotCompleteOnReturn();
      useLocalManualCompletion = true;
      return new CompletionAwareManualCompletionClient(
          manualCompletionClientFactory.getClient(info.getTaskToken(), metricsScope),
          completionHandle);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Scope getMetricsScope() {
    return metricsScope;
  }

  @Override
  public ActivityInfo getInfo() {
    return info;
  }
}
