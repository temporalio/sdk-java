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

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Objects;
import java.util.function.Supplier;

public class WorkflowPollTaskFactory
    implements Supplier<Poller.PollTask<PollWorkflowTaskQueueResponse>> {

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String taskQueue;
  private final Scope metricScope;
  private final String identity;
  private final String binaryChecksum;

  public WorkflowPollTaskFactory(
      WorkflowServiceStubs service,
      String namespace,
      String taskQueue,
      Scope metricScope,
      String identity,
      String binaryChecksum) {
    this.service = Objects.requireNonNull(service, "service should not be null");
    this.namespace = Objects.requireNonNull(namespace, "namespace should not be null");
    this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue should not be null");
    this.metricScope = Objects.requireNonNull(metricScope, "metricScope should not be null");
    this.identity = Objects.requireNonNull(identity, "identity should not be null");
    this.binaryChecksum = binaryChecksum;
  }

  @Override
  public Poller.PollTask<PollWorkflowTaskQueueResponse> get() {
    return new WorkflowPollTask(
        service, namespace, taskQueue, identity, binaryChecksum, metricScope);
  }
}
