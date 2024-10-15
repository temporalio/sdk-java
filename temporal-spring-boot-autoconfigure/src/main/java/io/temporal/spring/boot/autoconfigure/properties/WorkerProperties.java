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

package io.temporal.spring.boot.autoconfigure.properties;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.boot.context.properties.ConstructorBinding;

public class WorkerProperties {
  private final @Nonnull String taskQueue;
  private final @Nullable String name;
  private final @Nullable Collection<Class<?>> workflowClasses;
  private final @Nullable Collection<String> activityBeans;
  private final @Nullable Collection<String> nexusServiceBeans;
  private final @Nullable CapacityConfigurationProperties capacity;
  private final @Nullable RateLimitsConfigurationProperties rateLimits;
  private final @Nullable BuildIdConfigurationProperties buildId;

  @ConstructorBinding
  public WorkerProperties(
      @Nonnull String taskQueue,
      @Nullable String name,
      @Nullable Collection<Class<?>> workflowClasses,
      @Nullable Collection<String> activityBeans,
      @Nullable Collection<String> nexusServiceBeans,
      @Nullable CapacityConfigurationProperties capacity,
      @Nullable RateLimitsConfigurationProperties rateLimits,
      @Nullable BuildIdConfigurationProperties buildId) {
    this.name = name;
    this.taskQueue = taskQueue;
    this.workflowClasses = workflowClasses;
    this.activityBeans = activityBeans;
    this.nexusServiceBeans = nexusServiceBeans;
    this.capacity = capacity;
    this.rateLimits = rateLimits;
    this.buildId = buildId;
  }

  @Nonnull
  public String getTaskQueue() {
    return taskQueue;
  }

  @Nullable
  public String getName() {
    return name;
  }

  @Nullable
  public Collection<Class<?>> getWorkflowClasses() {
    return workflowClasses;
  }

  @Nullable
  public Collection<String> getActivityBeans() {
    return activityBeans;
  }

  @Nullable
  public CapacityConfigurationProperties getCapacity() {
    return capacity;
  }

  @Nullable
  public RateLimitsConfigurationProperties getRateLimits() {
    return rateLimits;
  }

  @Nullable
  public BuildIdConfigurationProperties getBuildId() {
    return buildId;
  }

  @Nullable
  public Collection<String> getNexusServiceBeans() {
    return nexusServiceBeans;
  }

  public static class CapacityConfigurationProperties {
    private final @Nullable Integer maxConcurrentWorkflowTaskExecutors;
    private final @Nullable Integer maxConcurrentActivityExecutors;
    private final @Nullable Integer maxConcurrentLocalActivityExecutors;
    private final @Nullable Integer maxConcurrentNexusTaskExecutors;
    private final @Nullable Integer maxConcurrentWorkflowTaskPollers;
    private final @Nullable Integer maxConcurrentActivityTaskPollers;
    private final @Nullable Integer maxConcurrentNexusTaskPollers;

    /**
     * @param maxConcurrentWorkflowTaskExecutors defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentWorkflowTaskPollers(int)}
     * @param maxConcurrentActivityExecutors defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentActivityExecutionSize(int)}
     * @param maxConcurrentLocalActivityExecutors defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentLocalActivityExecutionSize(int)}
     * @param maxConcurrentNexusTaskExecutors defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentNexusTaskPollers(int)} (int)}
     * @param maxConcurrentWorkflowTaskPollers defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentWorkflowTaskPollers(int)}
     * @param maxConcurrentActivityTaskPollers defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentActivityTaskPollers(int)}
     * @param maxConcurrentNexusTaskPollers defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentNexusTaskPollers(int)} (int)}
     */
    @ConstructorBinding
    public CapacityConfigurationProperties(
        @Nullable Integer maxConcurrentWorkflowTaskExecutors,
        @Nullable Integer maxConcurrentActivityExecutors,
        @Nullable Integer maxConcurrentLocalActivityExecutors,
        @Nullable Integer maxConcurrentNexusTaskExecutors,
        @Nullable Integer maxConcurrentWorkflowTaskPollers,
        @Nullable Integer maxConcurrentActivityTaskPollers,
        @Nullable Integer maxConcurrentNexusTaskPollers) {
      this.maxConcurrentWorkflowTaskExecutors = maxConcurrentWorkflowTaskExecutors;
      this.maxConcurrentActivityExecutors = maxConcurrentActivityExecutors;
      this.maxConcurrentLocalActivityExecutors = maxConcurrentLocalActivityExecutors;
      this.maxConcurrentNexusTaskExecutors = maxConcurrentNexusTaskExecutors;
      this.maxConcurrentWorkflowTaskPollers = maxConcurrentWorkflowTaskPollers;
      this.maxConcurrentActivityTaskPollers = maxConcurrentActivityTaskPollers;
      this.maxConcurrentNexusTaskPollers = maxConcurrentNexusTaskPollers;
    }

    @Nullable
    public Integer getMaxConcurrentWorkflowTaskExecutors() {
      return maxConcurrentWorkflowTaskExecutors;
    }

    @Nullable
    public Integer getMaxConcurrentActivityExecutors() {
      return maxConcurrentActivityExecutors;
    }

    @Nullable
    public Integer getMaxConcurrentLocalActivityExecutors() {
      return maxConcurrentLocalActivityExecutors;
    }

    @Nullable
    public Integer getMaxConcurrentNexusTasksExecutors() {
      return maxConcurrentNexusTaskExecutors;
    }

    @Nullable
    public Integer getMaxConcurrentWorkflowTaskPollers() {
      return maxConcurrentWorkflowTaskPollers;
    }

    @Nullable
    public Integer getMaxConcurrentActivityTaskPollers() {
      return maxConcurrentActivityTaskPollers;
    }

    @Nullable
    public Integer getMaxConcurrentNexusTaskPollers() {
      return maxConcurrentNexusTaskPollers;
    }
  }

  public static class RateLimitsConfigurationProperties {
    private final @Nullable Double maxWorkerActivitiesPerSecond;
    private final @Nullable Double maxTaskQueueActivitiesPerSecond;

    /**
     * @param maxTaskQueueActivitiesPerSecond defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxTaskQueueActivitiesPerSecond(double)}}
     * @param maxWorkerActivitiesPerSecond defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setMaxConcurrentActivityExecutionSize(int)}
     */
    @ConstructorBinding
    public RateLimitsConfigurationProperties(
        @Nullable Double maxWorkerActivitiesPerSecond,
        @Nullable Double maxTaskQueueActivitiesPerSecond) {
      this.maxWorkerActivitiesPerSecond = maxWorkerActivitiesPerSecond;
      this.maxTaskQueueActivitiesPerSecond = maxTaskQueueActivitiesPerSecond;
    }

    @Nullable
    public Double getMaxWorkerActivitiesPerSecond() {
      return maxWorkerActivitiesPerSecond;
    }

    @Nullable
    public Double getMaxTaskQueueActivitiesPerSecond() {
      return maxTaskQueueActivitiesPerSecond;
    }
  }

  public static class BuildIdConfigurationProperties {
    private final @Nullable String workerBuildId;
    private final @Nullable boolean enabledWorkerVersioning;

    /**
     * @param workerBuildId defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setBuildId(String)}}
     * @param enabledWorkerVersioning defines {@link
     *     io.temporal.worker.WorkerOptions.Builder#setUseBuildIdForVersioning(boolean)}
     */
    @ConstructorBinding
    public BuildIdConfigurationProperties(
        @Nullable String workerBuildId, @Nullable boolean enabledWorkerVersioning) {
      this.workerBuildId = workerBuildId;
      this.enabledWorkerVersioning = enabledWorkerVersioning;
    }

    @Nullable
    public String getWorkerBuildId() {
      return workerBuildId;
    }

    @Nullable
    public boolean getEnabledWorkerVersioning() {
      return enabledWorkerVersioning;
    }
  }
}
