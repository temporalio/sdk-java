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

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import java.lang.reflect.Type;
import java.util.Optional;

class LocalActivityExecutionContextImpl implements ActivityExecutionContext {
  private final ActivityInfo info;
  private final Scope metricsScope;

  LocalActivityExecutionContextImpl(ActivityInfo info, Scope metricsScope) {
    this.info = info;
    this.metricsScope = metricsScope;
  }

  @Override
  public ActivityInfo getInfo() {
    return info;
  }

  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    // Ignored
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return Optional.empty();
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    return Optional.empty();
  }

  @Override
  public byte[] getTaskToken() {
    throw new UnsupportedOperationException("getTaskToken is not supported for local activities");
  }

  @Override
  public void doNotCompleteOnReturn() {
    throw new UnsupportedOperationException(
        "doNotCompleteOnReturn is not supported for local activities");
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    throw new UnsupportedOperationException(
        "isDoNotCompleteOnReturn is not supported for local activities");
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    throw new UnsupportedOperationException(
        "isUseLocalManualCompletion is not supported for local activities");
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    throw new UnsupportedOperationException(
        "getManualCompletionClient is not supported for local activities");
  }

  @Override
  public Scope getMetricsScope() {
    return metricsScope;
  }
}
