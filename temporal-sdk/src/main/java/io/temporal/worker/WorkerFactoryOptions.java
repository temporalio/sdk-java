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

package io.temporal.worker;

import com.google.common.base.Preconditions;
import io.temporal.common.interceptors.WorkerInterceptor;
import java.time.Duration;

public class WorkerFactoryOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkerFactoryOptions options) {
    return new Builder(options);
  }

  public static WorkerFactoryOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final int DEFAULT_HOST_LOCAL_WORKFLOW_POLL_THREAD_COUNT = 5;
  private static final int DEFAULT_WORKFLOW_CACHE_SIZE = 600;
  private static final int DEFAULT_MAX_WORKFLOW_THREAD_COUNT = 600;
  private static final Duration DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT = Duration.ofSeconds(5);

  private static final WorkerFactoryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerFactoryOptions.newBuilder().build();
  }

  public static class Builder {

    private Duration workflowHostLocalTaskQueueScheduleToStartTimeout;
    private int workflowCacheSize;
    private int maxWorkflowThreadCount;
    private WorkerInterceptor[] workerInterceptors;
    private boolean enableLoggingInReplay;
    private int workflowHostLocalPollThreadCount;

    private Builder() {}

    private Builder(WorkerFactoryOptions options) {
      if (options == null) {
        return;
      }
      this.workflowHostLocalTaskQueueScheduleToStartTimeout =
          options.workflowHostLocalTaskQueueScheduleToStartTimeout;
      this.workflowCacheSize = options.workflowCacheSize;
      this.maxWorkflowThreadCount = options.maxWorkflowThreadCount;
      this.workerInterceptors = options.workerInterceptors;
      this.enableLoggingInReplay = options.enableLoggingInReplay;
      this.workflowHostLocalPollThreadCount = options.workflowHostLocalPollThreadCount;
    }

    /**
     * To avoid constant replay of code the workflow objects are cached on a worker. This cache is
     * shared by all workers created by the Factory. Note that in the majority of situations the
     * number of cached workflows is limited not by this value, but by the number of the threads
     * defined through {@link #setMaxWorkflowThreadCount(int)}.
     *
     * <p>Default value is 600
     */
    public Builder setWorkflowCacheSize(int workflowCacheSize) {
      this.workflowCacheSize = workflowCacheSize;
      return this;
    }

    /**
     * Maximum number of threads available for workflow execution across all workers created by the
     * Factory. This includes cached workflows.
     *
     * <p>Default is 600
     */
    public Builder setMaxWorkflowThreadCount(int maxWorkflowThreadCount) {
      this.maxWorkflowThreadCount = maxWorkflowThreadCount;
      return this;
    }

    /**
     * Timeout for a workflow task routed to the "sticky worker" - host that has the workflow
     * instance cached in memory. Once it times out, then it can be picked up by any worker.
     *
     * <p>Default value is 10 seconds.
     */
    public Builder setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration timeout) {
      this.workflowHostLocalTaskQueueScheduleToStartTimeout = timeout;
      return this;
    }

    public Builder setWorkerInterceptors(WorkerInterceptor... workerInterceptors) {
      this.workerInterceptors = workerInterceptors;
      return this;
    }

    public Builder setEnableLoggingInReplay(boolean enableLoggingInReplay) {
      this.enableLoggingInReplay = enableLoggingInReplay;
      return this;
    }

    public Builder setWorkflowHostLocalPollThreadCount(int workflowHostLocalPollThreadCount) {
      this.workflowHostLocalPollThreadCount = workflowHostLocalPollThreadCount;
      return this;
    }

    public WorkerFactoryOptions build() {
      return new WorkerFactoryOptions(
          workflowCacheSize,
          maxWorkflowThreadCount,
          workflowHostLocalTaskQueueScheduleToStartTimeout,
          workerInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          false);
    }

    public WorkerFactoryOptions validateAndBuildWithDefaults() {
      return new WorkerFactoryOptions(
          workflowCacheSize,
          maxWorkflowThreadCount,
          workflowHostLocalTaskQueueScheduleToStartTimeout,
          workerInterceptors == null ? new WorkerInterceptor[0] : workerInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          true);
    }
  }

  private final int workflowCacheSize;
  private final int maxWorkflowThreadCount;
  private final Duration workflowHostLocalTaskQueueScheduleToStartTimeout;
  private final WorkerInterceptor[] workerInterceptors;
  private final boolean enableLoggingInReplay;
  private final int workflowHostLocalPollThreadCount;

  private WorkerFactoryOptions(
      int workflowCacheSize,
      int maxWorkflowThreadCount,
      Duration workflowHostLocalTaskQueueScheduleToStartTimeout,
      WorkerInterceptor[] workerInterceptors,
      boolean enableLoggingInReplay,
      int workflowHostLocalPollThreadCount,
      boolean validate) {
    if (validate) {
      Preconditions.checkState(workflowCacheSize >= 0, "negative workflowCacheSize");
      if (workflowCacheSize <= 0) {
        workflowCacheSize = DEFAULT_WORKFLOW_CACHE_SIZE;
      }

      Preconditions.checkState(maxWorkflowThreadCount >= 0, "negative maxWorkflowThreadCount");
      if (maxWorkflowThreadCount == 0) {
        maxWorkflowThreadCount = DEFAULT_MAX_WORKFLOW_THREAD_COUNT;
      }
      if (workflowHostLocalTaskQueueScheduleToStartTimeout != null) {
        Preconditions.checkState(
            !workflowHostLocalTaskQueueScheduleToStartTimeout.isNegative(),
            "negative workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds");
      }
      if (workflowHostLocalTaskQueueScheduleToStartTimeout == null) {
        workflowHostLocalTaskQueueScheduleToStartTimeout = DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT;
      }
      if (workerInterceptors == null) {
        workerInterceptors = new WorkerInterceptor[0];
      }

      Preconditions.checkState(
          workflowHostLocalPollThreadCount >= 0, "negative workflowHostLocalPollThreadCount");
      if (workflowHostLocalPollThreadCount == 0) {
        workflowHostLocalPollThreadCount = DEFAULT_HOST_LOCAL_WORKFLOW_POLL_THREAD_COUNT;
      }
    }
    this.workflowCacheSize = workflowCacheSize;
    this.maxWorkflowThreadCount = maxWorkflowThreadCount;
    this.workflowHostLocalTaskQueueScheduleToStartTimeout =
        workflowHostLocalTaskQueueScheduleToStartTimeout;
    this.workerInterceptors = workerInterceptors;
    this.enableLoggingInReplay = enableLoggingInReplay;
    this.workflowHostLocalPollThreadCount = workflowHostLocalPollThreadCount;
  }

  public int getWorkflowCacheSize() {
    return workflowCacheSize;
  }

  public int getMaxWorkflowThreadCount() {
    return maxWorkflowThreadCount;
  }

  public Duration getWorkflowHostLocalTaskQueueScheduleToStartTimeout() {
    return workflowHostLocalTaskQueueScheduleToStartTimeout;
  }

  public WorkerInterceptor[] getWorkerInterceptors() {
    return workerInterceptors;
  }

  public boolean isEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  public int getWorkflowHostLocalPollThreadCount() {
    return workflowHostLocalPollThreadCount;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }
}
