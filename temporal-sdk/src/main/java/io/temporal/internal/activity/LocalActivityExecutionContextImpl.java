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
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import java.lang.reflect.Type;
import java.util.Optional;

class LocalActivityExecutionContextImpl implements InternalActivityExecutionContext {
  private final WorkflowClient client;
  private final Object activity;
  private final ActivityInfo info;
  private final Scope metricsScope;

  LocalActivityExecutionContextImpl(
      WorkflowClient client, Object activity, ActivityInfo info, Scope metricsScope) {
    this.client = client;
    this.activity = activity;
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
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
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
    return false;
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    return false;
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

  @Override
  public Object getLastHeartbeatValue() {
    return null;
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return client;
  }

  @Override
  public Object getInstance() {
    return activity;
  }
}
