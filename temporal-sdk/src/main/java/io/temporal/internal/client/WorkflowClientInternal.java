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

package io.temporal.internal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Functions;

/**
 * From OOP point of view, there is no reason for this interface not to extend {@link
 * WorkflowClient}. It's not extending only to make sure that the calls that can come through a
 * user-supplied WorkflowClient are made through that entity whenever possible instead of the entity
 * obtained by {@link WorkflowClient#getInternal()}. This will make sure DI/AOP frameworks or user's
 * wrapping code implemented in WorkflowClient proxy or adapter gets executed when possible and
 * {@link WorkflowClient#getInternal()} is used only for internal functionality.
 */
public interface WorkflowClientInternal {
  void registerWorkerFactory(WorkerFactory workerFactory);

  void deregisterWorkerFactory(WorkerFactory workerFactory);

  WorkflowExecution startNexus(NexusStartWorkflowRequest request, Functions.Proc workflow);
}
