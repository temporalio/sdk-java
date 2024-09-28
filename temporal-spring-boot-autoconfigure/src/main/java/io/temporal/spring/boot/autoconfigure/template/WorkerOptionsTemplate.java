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
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.worker.WorkerOptions;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class WorkerOptionsTemplate {
  private final @Nonnull String taskQueue;
  private final @Nonnull String workerName;
  private final @Nullable WorkerProperties workerProperties;
  private final @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> customizer;

  WorkerOptionsTemplate(
      @Nonnull String workerName,
      @Nonnull String taskQueue,
      @Nullable WorkerProperties workerProperties,
      @Nullable TemporalOptionsCustomizer<WorkerOptions.Builder> customizer) {
    this.workerName = workerName;
    this.taskQueue = taskQueue;
    this.workerProperties = workerProperties;
    this.customizer = customizer;
  }

  WorkerOptions createWorkerOptions() {
    WorkerOptions.Builder options = WorkerOptions.newBuilder();

    if (workerProperties != null) {
      WorkerProperties.CapacityConfigurationProperties threadsConfiguration =
          workerProperties.getCapacity();
      if (threadsConfiguration != null) {
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentWorkflowTaskExecutors())
            .ifPresent(options::setMaxConcurrentWorkflowTaskExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentActivityExecutors())
            .ifPresent(options::setMaxConcurrentActivityExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentLocalActivityExecutors())
            .ifPresent(options::setMaxConcurrentLocalActivityExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentNexusTasksExecutors())
            .ifPresent(options::setMaxConcurrentNexusExecutionSize);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentWorkflowTaskPollers())
            .ifPresent(options::setMaxConcurrentWorkflowTaskPollers);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentActivityTaskPollers())
            .ifPresent(options::setMaxConcurrentActivityTaskPollers);
        Optional.ofNullable(threadsConfiguration.getMaxConcurrentNexusTaskPollers())
            .ifPresent(options::setMaxConcurrentNexusTaskPollers);
      }

      WorkerProperties.RateLimitsConfigurationProperties rateLimitConfiguration =
          workerProperties.getRateLimits();
      if (rateLimitConfiguration != null) {
        Optional.ofNullable(rateLimitConfiguration.getMaxWorkerActivitiesPerSecond())
            .ifPresent(options::setMaxWorkerActivitiesPerSecond);
        Optional.ofNullable(rateLimitConfiguration.getMaxTaskQueueActivitiesPerSecond())
            .ifPresent(options::setMaxTaskQueueActivitiesPerSecond);
      }

      WorkerProperties.BuildIdConfigurationProperties buildIdConfigurations =
          workerProperties.getBuildId();
      if (buildIdConfigurations != null) {
        Optional.ofNullable(buildIdConfigurations.getWorkerBuildId())
            .ifPresent(options::setBuildId);
        Optional.ofNullable(buildIdConfigurations.getEnabledWorkerVersioning())
            .ifPresent(options::setUseBuildIdForVersioning);
      }
    }
    if (customizer != null) {
      options = customizer.customize(options);
      if (customizer instanceof WorkerOptionsCustomizer) {
        options = ((WorkerOptionsCustomizer) customizer).customize(options, workerName, taskQueue);
      }
    }

    return options.build();
  }
}
