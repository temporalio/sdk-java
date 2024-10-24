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

package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusOperationContext;

public class NexusOperationContextImpl implements NexusOperationContext {
  private final String namespace;
  private final String taskQueue;
  private final WorkflowClient client;
  NexusOperationOutboundCallsInterceptor outboundCalls;

  public NexusOperationContextImpl(
      String namespace,
      String taskQueue,
      WorkflowClient client,
      NexusOperationOutboundCallsInterceptor outboundCalls) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.client = client;
    this.outboundCalls = outboundCalls;
  }

  @Override
  public Scope getMetricsScope() {
    return outboundCalls.getMetricsScope();
  }

  public WorkflowClient getWorkflowClient() {
    return client;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setOutboundInterceptor(NexusOperationOutboundCallsInterceptor outboundCalls) {
    this.outboundCalls = outboundCalls;
  }
}
