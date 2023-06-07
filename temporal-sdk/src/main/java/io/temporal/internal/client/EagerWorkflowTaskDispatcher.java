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

import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkflowTaskDispatchHandle;
import javax.annotation.Nullable;

class EagerWorkflowTaskDispatcher {
  private final WorkerFactoryRegistry workerFactories;

  public EagerWorkflowTaskDispatcher(WorkerFactoryRegistry workerFactories) {
    this.workerFactories = workerFactories;
  }

  @Nullable
  public WorkflowTaskDispatchHandle tryGetLocalDispatchHandler(
      WorkflowClientCallsInterceptor.WorkflowStartInput workflowStartInput) {
    for (WorkerFactory workerFactory : workerFactories.workerFactoriesRandomOrder()) {
      Worker worker = workerFactory.tryGetWorker(workflowStartInput.getOptions().getTaskQueue());
      if (worker != null) {
        WorkflowTaskDispatchHandle workflowTaskDispatchHandle = worker.reserveWorkflowExecutor();
        if (workflowTaskDispatchHandle != null) {
          return workflowTaskDispatchHandle;
        }
      }
    }
    return null;
  }
}
