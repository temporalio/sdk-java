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

package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import java.lang.reflect.Type;
import java.util.Optional;

/** Convenience class for implementing ActivityInterceptors. */
public class ActivityExecutionContextBase implements ActivityExecutionContext {
  private final ActivityExecutionContext next;

  public ActivityExecutionContextBase(ActivityExecutionContext next) {
    this.next = next;
  }

  @Override
  public ActivityInfo getInfo() {
    return next.getInfo();
  }

  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    next.heartbeat(details);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return next.getHeartbeatDetails(detailsClass);
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return next.getHeartbeatDetails(detailsClass, detailsType);
  }

  @Override
  public byte[] getTaskToken() {
    return next.getTaskToken();
  }

  @Override
  public void doNotCompleteOnReturn() {
    next.doNotCompleteOnReturn();
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    return next.isDoNotCompleteOnReturn();
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    return next.isUseLocalManualCompletion();
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    return next.useLocalManualCompletion();
  }

  @Override
  public Scope getMetricsScope() {
    return next.getMetricsScope();
  }
}
