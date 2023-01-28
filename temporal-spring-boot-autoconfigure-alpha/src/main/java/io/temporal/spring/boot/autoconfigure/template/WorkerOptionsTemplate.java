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

package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.WorkerOptionsCustomizer;
import io.temporal.worker.WorkerOptions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WorkerOptionsTemplate {
  private final @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> customizer;
  private final @Nonnull String taskQueue;
  private final @Nonnull String workerName;

  WorkerOptionsTemplate(
      @Nonnull String workerName,
      @Nonnull String taskQueue,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> customizer) {
    this.workerName = workerName;
    this.taskQueue = taskQueue;
    this.customizer = customizer;
  }

  WorkerOptions createWorkerOptions() {
    WorkerOptions.Builder options = WorkerOptions.newBuilder();

    if (customizer != null) {
      options = customizer.customize(options);
      if (customizer instanceof WorkerOptionsCustomizer) {
        options = ((WorkerOptionsCustomizer) customizer).customize(options, workerName, taskQueue);
      }
    }

    return options.build();
  }
}
