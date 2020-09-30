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

package io.temporal.worker;

import com.google.common.base.Preconditions;
import io.temporal.common.interceptors.ActivityInterceptor;
import io.temporal.common.interceptors.WorkflowInterceptor;
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

  private static final WorkerFactoryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerFactoryOptions.newBuilder().build();
  }

  public static class Builder {
    private Duration workflowHostLocalTaskQueueScheduleToStartTimeout;
    private int workflowCacheSize;
    private int maxWorkflowThreadCount;
    private WorkflowInterceptor[] workflowInterceptors;
    private ActivityInterceptor[] activityInterceptors;
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
      this.workflowInterceptors = options.workflowInterceptors;
      this.activityInterceptors = options.activityInterceptors;
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
     * Timeout for a workflow task routed to the the host that caches a workflow object. Once it
     * times out then it can be picked up by any worker.
     *
     * <p>Default value is 10 seconds.
     */
    public Builder setWorkflowHostLocalTaskQueueScheduleToStartTimeout(Duration timeout) {
      this.workflowHostLocalTaskQueueScheduleToStartTimeout = timeout;
      return this;
    }

    public Builder setWorkflowInterceptors(WorkflowInterceptor... workflowInterceptors) {
      this.workflowInterceptors = workflowInterceptors;
      return this;
    }

    public Builder setActivityInterceptors(ActivityInterceptor... activityInterceptors) {
      this.activityInterceptors = activityInterceptors;
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
          workflowInterceptors,
          activityInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          false);
    }

    public WorkerFactoryOptions validateAndBuildWithDefaults() {
      return new WorkerFactoryOptions(
          workflowCacheSize,
          maxWorkflowThreadCount,
          workflowHostLocalTaskQueueScheduleToStartTimeout,
          workflowInterceptors == null ? new WorkflowInterceptor[0] : workflowInterceptors,
          activityInterceptors == null ? new ActivityInterceptor[0] : activityInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          true);
    }
  }

  private final int workflowCacheSize;
  private final int maxWorkflowThreadCount;
  private final Duration workflowHostLocalTaskQueueScheduleToStartTimeout;
  private final WorkflowInterceptor[] workflowInterceptors;
  private final ActivityInterceptor[] activityInterceptors;
  private final boolean enableLoggingInReplay;
  private final int workflowHostLocalPollThreadCount;

  private WorkerFactoryOptions(
      int workflowCacheSize,
      int maxWorkflowThreadCount,
      Duration workflowHostLocalTaskQueueScheduleToStartTimeout,
      WorkflowInterceptor[] workflowInterceptors,
      ActivityInterceptor[] activityInterceptors,
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
      if (workflowInterceptors == null) {
        workflowInterceptors = new WorkflowInterceptor[0];
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
    this.workflowInterceptors = workflowInterceptors;
    this.activityInterceptors = activityInterceptors;
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

  public WorkflowInterceptor[] getWorkflowInterceptors() {
    return workflowInterceptors;
  }

  public ActivityInterceptor[] getActivityInterceptors() {
    return activityInterceptors;
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
