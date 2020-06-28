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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public final class WorkerOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(WorkerOptions options) {
    return new Builder(options);
  }

  public static WorkerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final WorkerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerOptions.newBuilder().build();
  }

  public static final class Builder {

    private static final int DEFAULT_WORKFLOW_POLL_THREAD_COUNT = 2;
    private static final int DEFAULT_ACTIVITY_POLL_THREAD_COUNT = 5;
    private static final int DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 200;
    private static final int DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 200;
    private static final int DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 200;

    private double maxActivitiesPerSecond;
    private int maxConcurrentActivityExecutionSize;
    private int maxConcurrentWorkflowTaskExecutionSize;
    private int maxConcurrentLocalActivityExecutionSize;
    private double taskQueueActivitiesPerSecond;
    private int workflowPollThreadCount;
    private int activityPollThreadCount;

    private Builder() {}

    private Builder(WorkerOptions o) {
      if (o == null) {
        return;
      }
      maxActivitiesPerSecond = o.maxActivitiesPerSecond;
      maxConcurrentActivityExecutionSize = o.maxConcurrentActivityExecutionSize;
      maxConcurrentWorkflowTaskExecutionSize = o.maxConcurrentWorkflowTaskExecutionSize;
      maxConcurrentLocalActivityExecutionSize = o.maxConcurrentLocalActivityExecutionSize;
      taskQueueActivitiesPerSecond = o.taskQueueActivitiesPerSecond;
      workflowPollThreadCount = o.workflowPollThreadCount;
      activityPollThreadCount = o.activityPollThreadCount;
    }

    /**
     * Maximum number of activities started per second by this worker. Default is 0 which means
     * unlimited. If worker is not fully loaded while tasks are backing up on the service consider
     * increasing {@link #setActivityPollThreadCount(int)}.
     *
     * <p>Note that this is a per worker limit. Use {@link #setTaskQueueActivitiesPerSecond(double)}
     * to set per task queue limit across multiple workers.
     */
    public Builder setMaxActivitiesPerSecond(double maxActivitiesPerSecond) {
      if (maxActivitiesPerSecond <= 0) {
        throw new IllegalArgumentException("Negative or zero: " + maxActivitiesPerSecond);
      }
      this.maxActivitiesPerSecond = maxActivitiesPerSecond;
      return this;
    }

    /**
     * Maximum number of parallely executed activities.
     *
     * <p>Default is 200.
     */
    public Builder setMaxConcurrentActivityExecutionSize(int maxConcurrentActivityExecutionSize) {
      if (maxConcurrentActivityExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentActivityExecutionSize);
      }
      this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
      return this;
    }

    /**
     * Maximum number of simultaneously executed workflow tasks. Note that this is not related to
     * the total number of open workflows which do not need to be loaded in a worker when they are
     * not making state transitions.
     *
     * <p>Default is 200.
     */
    public Builder setMaxConcurrentWorkflowTaskExecutionSize(
        int maxConcurrentWorkflowTaskExecutionSize) {
      if (maxConcurrentWorkflowTaskExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentWorkflowTaskExecutionSize);
      }
      this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowTaskExecutionSize;
      return this;
    }

    /**
     * Maximum number of parallely executed local activities.
     *
     * <p>Default is 200.
     */
    public Builder setMaxConcurrentLocalActivityExecutionSize(
        int maxConcurrentLocalActivityExecutionSize) {
      if (maxConcurrentLocalActivityExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentLocalActivityExecutionSize);
      }
      this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
      return this;
    }

    /**
     * Optional: Sets the rate limiting on number of activities that can be executed per second.
     * This is managed by the server and controls activities per second for your entire taskqueue.
     * Notice that the number is represented in double, so that you can set it to less than 1 if
     * needed. For example, set the number to 0.1 means you want your activity to be executed once
     * every 10 seconds. This can be used to protect down stream services from flooding. The zero
     * value of this uses the default value. Default is unlimited.
     */
    public Builder setTaskQueueActivitiesPerSecond(double taskQueueActivitiesPerSecond) {
      this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
      return this;
    }

    /**
     * Number of simultaneous poll requests on workflow task queue. Note that the majority of the
     * workflow tasks will be using host local task queue due to caching. So try incrementing {@link
     * WorkerFactoryOptions.Builder#setWorkflowHostLocalPollThreadCount(int)} before this one.
     *
     * <p>Default is 2.
     */
    public Builder setWorkflowPollThreadCount(int workflowPollThreadCount) {
      this.workflowPollThreadCount = workflowPollThreadCount;
      return this;
    }

    /**
     * Number of simultaneous poll requests on activity task queue. Consider incrementing if the
     * worker is not throttled due to `MaxActivitiesPerSecond` or
     * `MaxConcurrentActivityExecutionSize` options and still cannot keep up with the request rate.
     *
     * <p>Default is 5.
     */
    public Builder setActivityPollThreadCount(int activityPollThreadCount) {
      this.activityPollThreadCount = activityPollThreadCount;
      return this;
    }

    public WorkerOptions build() {
      return new WorkerOptions(
          maxActivitiesPerSecond,
          maxConcurrentActivityExecutionSize,
          DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE,
          DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE,
          taskQueueActivitiesPerSecond,
          workflowPollThreadCount,
          activityPollThreadCount);
    }

    public WorkerOptions validateAndBuildWithDefaults() {
      Preconditions.checkState(maxActivitiesPerSecond >= 0, "negative maxActivitiesPerSecond");
      Preconditions.checkState(
          maxConcurrentActivityExecutionSize >= 0, "negative maxConcurrentActivityExecutionSize");
      Preconditions.checkState(
          maxConcurrentWorkflowTaskExecutionSize >= 0,
          "negative maxConcurrentWorkflowTaskExecutionSize");
      Preconditions.checkState(
          maxConcurrentLocalActivityExecutionSize >= 0,
          "negative maxConcurrentLocalActivityExecutionSize");
      Preconditions.checkState(
          taskQueueActivitiesPerSecond >= 0, "negative taskQueueActivitiesPerSecond");
      Preconditions.checkState(workflowPollThreadCount >= 0, "negative workflowPollThreadCount");
      Preconditions.checkState(activityPollThreadCount >= 0, "negative activityPollThreadCount");
      return new WorkerOptions(
          maxActivitiesPerSecond,
          maxConcurrentActivityExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE
              : maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowTaskExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE
              : maxConcurrentWorkflowTaskExecutionSize,
          maxConcurrentLocalActivityExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE
              : maxConcurrentLocalActivityExecutionSize,
          taskQueueActivitiesPerSecond,
          workflowPollThreadCount == 0
              ? DEFAULT_WORKFLOW_POLL_THREAD_COUNT
              : workflowPollThreadCount,
          activityPollThreadCount == 0
              ? DEFAULT_ACTIVITY_POLL_THREAD_COUNT
              : activityPollThreadCount);
    }
  }

  private final double maxActivitiesPerSecond;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowTaskExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double taskQueueActivitiesPerSecond;
  private final int workflowPollThreadCount;
  private final int activityPollThreadCount;

  private WorkerOptions(
      double maxActivitiesPerSecond,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double taskQueueActivitiesPerSecond,
      int workflowPollThreadCount,
      int activityPollThreadCount) {
    this.maxActivitiesPerSecond = maxActivitiesPerSecond;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.taskQueueActivitiesPerSecond = taskQueueActivitiesPerSecond;
    this.workflowPollThreadCount = workflowPollThreadCount;
    this.activityPollThreadCount = activityPollThreadCount;
  }

  public double getMaxActivitiesPerSecond() {
    return maxActivitiesPerSecond;
  }

  public int getMaxConcurrentActivityExecutionSize() {
    return maxConcurrentActivityExecutionSize;
  }

  public int getMaxConcurrentWorkflowTaskExecutionSize() {
    return maxConcurrentWorkflowTaskExecutionSize;
  }

  public int getMaxConcurrentLocalActivityExecutionSize() {
    return maxConcurrentLocalActivityExecutionSize;
  }

  public double getTaskQueueActivitiesPerSecond() {
    return taskQueueActivitiesPerSecond;
  }

  public int getWorkflowPollThreadCount() {
    return workflowPollThreadCount;
  }

  public int getActivityPollThreadCount() {
    return activityPollThreadCount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkerOptions that = (WorkerOptions) o;
    return Double.compare(that.maxActivitiesPerSecond, maxActivitiesPerSecond) == 0
        && maxConcurrentActivityExecutionSize == that.maxConcurrentActivityExecutionSize
        && maxConcurrentWorkflowTaskExecutionSize == that.maxConcurrentWorkflowTaskExecutionSize
        && maxConcurrentLocalActivityExecutionSize == that.maxConcurrentLocalActivityExecutionSize
        && Double.compare(that.taskQueueActivitiesPerSecond, taskQueueActivitiesPerSecond) == 0
        && workflowPollThreadCount == that.workflowPollThreadCount
        && activityPollThreadCount == that.activityPollThreadCount;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        maxActivitiesPerSecond,
        maxConcurrentActivityExecutionSize,
        maxConcurrentWorkflowTaskExecutionSize,
        maxConcurrentLocalActivityExecutionSize,
        taskQueueActivitiesPerSecond,
        workflowPollThreadCount,
        activityPollThreadCount);
  }

  @Override
  public String toString() {
    return "WorkerOptions{"
        + "maxActivitiesPerSecond="
        + maxActivitiesPerSecond
        + ", maxConcurrentActivityExecutionSize="
        + maxConcurrentActivityExecutionSize
        + ", maxConcurrentWorkflowTaskExecutionSize="
        + maxConcurrentWorkflowTaskExecutionSize
        + ", maxConcurrentLocalActivityExecutionSize="
        + maxConcurrentLocalActivityExecutionSize
        + ", taskQueueActivitiesPerSecond="
        + taskQueueActivitiesPerSecond
        + ", workflowPollThreadCount="
        + workflowPollThreadCount
        + ", activityPollThreadCount="
        + activityPollThreadCount
        + '}';
  }
}
