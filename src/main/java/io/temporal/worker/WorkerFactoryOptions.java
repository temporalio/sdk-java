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
import io.temporal.common.interceptors.WorkflowInterceptor;

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
  private static final int DEFAULT_WORKFLOW_HOST_LOCAL_TASK_QUEUE_SCHEDULE_TO_START_TIMEOUT = 10;

  private static final WorkerFactoryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerFactoryOptions.newBuilder().build();
  }

  public static class Builder {
    private int workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds =
        DEFAULT_WORKFLOW_HOST_LOCAL_TASK_QUEUE_SCHEDULE_TO_START_TIMEOUT;
    private int workflowCacheSize;
    private int maxWorkflowThreadCount;
    private WorkflowInterceptor[] workflowInterceptors;
    private boolean enableLoggingInReplay;
    private int workflowHostLocalPollThreadCount;

    private Builder() {}

    private Builder(WorkerFactoryOptions options) {
      if (options == null) {
        return;
      }
      this.workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds =
          options.workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds;
      this.workflowCacheSize = options.workflowCacheSize;
      this.maxWorkflowThreadCount = options.maxWorkflowThreadCount;
      this.workflowInterceptors = options.workflowInterceptors;
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
    public Builder setWorkflowHostLocalTaskQueueScheduleToStartTimeoutSeconds(
        int workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds) {
      this.workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds =
          workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds;
      return this;
    }

    public Builder setWorkflowInterceptors(WorkflowInterceptor... workflowInterceptors) {
      this.workflowInterceptors = workflowInterceptors;
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
          workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds,
          workflowInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          false);
    }

    public WorkerFactoryOptions validateAndBuildWithDefaults() {
      return new WorkerFactoryOptions(
          workflowCacheSize,
          maxWorkflowThreadCount,
          workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds,
          workflowInterceptors,
          enableLoggingInReplay,
          workflowHostLocalPollThreadCount,
          true);
    }
  }

  private final int workflowCacheSize;
  private final int maxWorkflowThreadCount;
  private final int workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds;
  private final WorkflowInterceptor[] workflowInterceptors;
  private final boolean enableLoggingInReplay;
  private final int workflowHostLocalPollThreadCount;

  private WorkerFactoryOptions(
      int workflowCacheSize,
      int maxWorkflowThreadCount,
      int workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds,
      WorkflowInterceptor[] workflowInterceptors,
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
      Preconditions.checkState(
          workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds >= 0,
          "negative workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds");

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
    this.workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds =
        workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds;
    this.workflowInterceptors = workflowInterceptors;
    this.enableLoggingInReplay = enableLoggingInReplay;
    this.workflowHostLocalPollThreadCount = workflowHostLocalPollThreadCount;
  }

  public int getWorkflowCacheSize() {
    return workflowCacheSize;
  }

  public int getMaxWorkflowThreadCount() {
    return maxWorkflowThreadCount;
  }

  public int getWorkflowHostLocalTaskQueueScheduleToStartTimeoutSeconds() {
    return workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds;
  }

  public WorkflowInterceptor[] getWorkflowInterceptors() {
    return workflowInterceptors;
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
