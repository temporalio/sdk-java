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

import io.temporal.workflow.WorkflowCallsInterceptor;
import io.temporal.workflow.WorkflowExecutionInterceptor;

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
    private int stickyDecisionScheduleToStartTimeoutInSeconds;
    private int cacheMaximumSize;
    private int maxWorkflowThreadCount;
    private WorkflowExecutionInterceptor interceptorFactory;
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
      this.interceptorFactory = options.interceptorFactory;
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

    public Builder setInterceptorFactory(WorkflowExecutionInterceptor interceptorFactory) {
      this.interceptorFactory = interceptorFactory;
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
          interceptorFactory,
          enableLoggingInReplay,
          false);
    }

    public WorkerFactoryOptions validateAndBuildWithDefaults() {
      return new WorkerFactoryOptions(
          cacheMaximumSize,
          maxWorkflowThreadCount,
          stickyDecisionScheduleToStartTimeoutInSeconds,
          interceptorFactory,
          enableLoggingInReplay,
          true);
    }
  }

  private final int cacheMaximumSize;
  private final int maxWorkflowThreadCount;
  private final int stickyDecisionScheduleToStartTimeoutInSeconds;
  private final WorkflowExecutionInterceptor interceptorFactory;
  private final boolean enableLoggingInReplay;

  private WorkerFactoryOptions(
      int cacheMaximumSize,
      int maxWorkflowThreadCount,
      int stickyDecisionScheduleToStartTimeoutInSeconds,
      WorkflowExecutionInterceptor interceptorFactory,
      boolean enableLoggingInReplay,
      boolean validate) {
    if (validate) {
      if (cacheMaximumSize <= 0) {
        cacheMaximumSize = 600;
      }
      if (maxWorkflowThreadCount <= 0) {
        maxWorkflowThreadCount = 600;
      }
      if (stickyDecisionScheduleToStartTimeoutInSeconds <= 0) {
        stickyDecisionScheduleToStartTimeoutInSeconds = 5;
      }
      if (interceptorFactory == null) {
        interceptorFactory = new NoopWorkflowExecutionInterceptor();
      }
    }
    this.cacheMaximumSize = cacheMaximumSize;
    this.maxWorkflowThreadCount = maxWorkflowThreadCount;
    this.stickyDecisionScheduleToStartTimeoutInSeconds =
        stickyDecisionScheduleToStartTimeoutInSeconds;
    this.interceptorFactory = interceptorFactory;
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

  public WorkflowExecutionInterceptor getInterceptorFactory() {
    return interceptorFactory;
  }

  public boolean isEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  static class NoopWorkflowExecutionInterceptor implements WorkflowExecutionInterceptor {
    @Override
    public WorkflowCallsInterceptor interceptExecuteWorkflow(
        String workflowType, Object[] arguments, WorkflowCallsInterceptor next) {
      return next;
    }
  }
}
