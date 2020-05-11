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

import io.temporal.common.interceptors.NoopWorkflowInterceptor;
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

  private static final WorkerFactoryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerFactoryOptions.newBuilder().build();
  }

  public static class Builder {
    private int stickyDecisionScheduleToStartTimeoutInSeconds = 10;
    private int cacheMaximumSize;
    private int maxWorkflowThreadCount;
    private WorkflowInterceptor workflowInterceptor;
    private boolean enableLoggingInReplay;

    private Builder() {}

    private Builder(WorkerFactoryOptions options) {
      if (options == null) {
        return;
      }
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          options.stickyDecisionScheduleToStartTimeoutInSeconds;
      this.cacheMaximumSize = options.cacheMaximumSize;
      this.maxWorkflowThreadCount = options.maxWorkflowThreadCount;
      this.workflowInterceptor = options.workflowInterceptor;
      this.enableLoggingInReplay = options.enableLoggingInReplay;
    }

    /**
     * When Sticky execution is enabled this will set the maximum allowed number of workflows
     * cached. This cache is shared by all workers created by the Factory. Default value is 600
     */
    public Builder setCacheMaximumSize(int cacheMaximumSize) {
      this.cacheMaximumSize = cacheMaximumSize;
      return this;
    }

    /**
     * Maximum number of threads available for workflow execution across all workers created by the
     * Factory.
     */
    public Builder setMaxWorkflowThreadCount(int maxWorkflowThreadCount) {
      this.maxWorkflowThreadCount = maxWorkflowThreadCount;
      return this;
    }

    /**
     * Timeout for sticky workflow decision to be picked up by the host assigned to it. Once it
     * times out then it can be picked up by any worker. Default value is 5 seconds.
     */
    public Builder setStickyDecisionScheduleToStartTimeoutInSeconds(
        int stickyDecisionScheduleToStartTimeoutInSeconds) {
      this.stickyDecisionScheduleToStartTimeoutInSeconds =
          stickyDecisionScheduleToStartTimeoutInSeconds;
      return this;
    }

    // TODO: List of interceptors
    public Builder setWorkflowInterceptor(WorkflowInterceptor workflowInterceptor) {
      this.workflowInterceptor = workflowInterceptor;
      return this;
    }

    public Builder setEnableLoggingInReplay(boolean enableLoggingInReplay) {
      this.enableLoggingInReplay = enableLoggingInReplay;
      return this;
    }

    public WorkerFactoryOptions build() {
      return new WorkerFactoryOptions(
          cacheMaximumSize,
          maxWorkflowThreadCount,
          stickyDecisionScheduleToStartTimeoutInSeconds,
          workflowInterceptor,
          enableLoggingInReplay,
          false);
    }

    public WorkerFactoryOptions validateAndBuildWithDefaults() {
      return new WorkerFactoryOptions(
          cacheMaximumSize,
          maxWorkflowThreadCount,
          stickyDecisionScheduleToStartTimeoutInSeconds,
          workflowInterceptor,
          enableLoggingInReplay,
          true);
    }
  }

  private final int cacheMaximumSize;
  private final int maxWorkflowThreadCount;
  private final int stickyDecisionScheduleToStartTimeoutInSeconds;
  private final WorkflowInterceptor workflowInterceptor;
  private final boolean enableLoggingInReplay;

  private WorkerFactoryOptions(
      int cacheMaximumSize,
      int maxWorkflowThreadCount,
      int stickyDecisionScheduleToStartTimeoutInSeconds,
      WorkflowInterceptor workflowInterceptor,
      boolean enableLoggingInReplay,
      boolean validate) {
    if (validate) {
      if (cacheMaximumSize <= 0) {
        cacheMaximumSize = 600;
      }
      if (maxWorkflowThreadCount <= 0) {
        maxWorkflowThreadCount = 600;
      }
      if (workflowInterceptor == null) {
        workflowInterceptor = new NoopWorkflowInterceptor();
      }
    }
    this.cacheMaximumSize = cacheMaximumSize;
    this.maxWorkflowThreadCount = maxWorkflowThreadCount;
    this.stickyDecisionScheduleToStartTimeoutInSeconds =
        stickyDecisionScheduleToStartTimeoutInSeconds;
    this.workflowInterceptor = workflowInterceptor;
    this.enableLoggingInReplay = enableLoggingInReplay;
  }

  public int getCacheMaximumSize() {
    return cacheMaximumSize;
  }

  public int getMaxWorkflowThreadCount() {
    return maxWorkflowThreadCount;
  }

  public int getStickyDecisionScheduleToStartTimeoutInSeconds() {
    return stickyDecisionScheduleToStartTimeoutInSeconds;
  }

  public WorkflowInterceptor getWorkflowInterceptor() {
    return workflowInterceptor;
  }

  public boolean isEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }
}
