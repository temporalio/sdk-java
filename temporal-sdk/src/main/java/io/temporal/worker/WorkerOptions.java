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

import static java.lang.Double.compare;

import com.google.common.base.Preconditions;
import java.util.Objects;

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
    private static final long DEFAULT_DEADLOCK_DETECTION_TIMEOUT = 1000;

    private double maxWorkerActivitiesPerSecond;
    private int maxConcurrentActivityExecutionSize;
    private int maxConcurrentWorkflowTaskExecutionSize;
    private int maxConcurrentLocalActivityExecutionSize;
    private double maxTaskQueueActivitiesPerSecond;
    private int workflowPollThreadCount;
    private int activityPollThreadCount;
    private boolean localActivityWorkerOnly;
    private long defaultDeadlockDetectionTimeout;

    private Builder() {}

    private Builder(WorkerOptions o) {
      if (o == null) {
        return;
      }
      maxWorkerActivitiesPerSecond = o.maxWorkerActivitiesPerSecond;
      maxConcurrentActivityExecutionSize = o.maxConcurrentActivityExecutionSize;
      maxConcurrentWorkflowTaskExecutionSize = o.maxConcurrentWorkflowTaskExecutionSize;
      maxConcurrentLocalActivityExecutionSize = o.maxConcurrentLocalActivityExecutionSize;
      maxTaskQueueActivitiesPerSecond = o.maxTaskQueueActivitiesPerSecond;
      workflowPollThreadCount = o.workflowPollThreadCount;
      activityPollThreadCount = o.activityPollThreadCount;
      localActivityWorkerOnly = o.localActivityWorkerOnly;
      defaultDeadlockDetectionTimeout = o.defaultDeadlockDetectionTimeout;
    }

    /**
     * Maximum number of activities started per second by this worker. Default is 0 which means
     * unlimited. If worker is not fully loaded while tasks are backing up on the service consider
     * increasing {@link #setActivityPollThreadCount(int)}.
     *
     * <p>Note that this is a per worker limit. Use {@link
     * #setMaxTaskQueueActivitiesPerSecond(double)} to set per task queue limit across multiple
     * workers.
     */
    public Builder setMaxWorkerActivitiesPerSecond(double maxWorkerActivitiesPerSecond) {
      if (maxWorkerActivitiesPerSecond <= 0) {
        throw new IllegalArgumentException("Negative or zero: " + maxWorkerActivitiesPerSecond);
      }
      this.maxWorkerActivitiesPerSecond = maxWorkerActivitiesPerSecond;
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
     * This is managed by the server and controls activities per second for the entire task queue
     * across all the workers. Notice that the number is represented in double, so that you can set
     * it to less than 1 if needed. For example, set the number to 0.1 means you want your activity
     * to be executed once every 10 seconds. This can be used to protect down stream services from
     * flooding. The zero value of this uses the default value. Default is unlimited.
     */
    public Builder setMaxTaskQueueActivitiesPerSecond(double maxTaskQueueActivitiesPerSecond) {
      this.maxTaskQueueActivitiesPerSecond = maxTaskQueueActivitiesPerSecond;
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

    /**
     * If set to true worker would only handle workflow tasks and local activities. Non-local
     * activities will not be executed by this worker.
     *
     * <p>Default is false.
     */
    public Builder setLocalActivityWorkerOnly(boolean localActivityWorkerOnly) {
      this.localActivityWorkerOnly = localActivityWorkerOnly;
      return this;
    }

    public Builder setDefaultDeadlockDetectionTimeout(long defaultDeadlockDetectionTimeout) {
      this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
      return this;
    }

    public WorkerOptions build() {
      return new WorkerOptions(
          maxWorkerActivitiesPerSecond,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowTaskExecutionSize,
          maxConcurrentActivityExecutionSize,
          maxTaskQueueActivitiesPerSecond,
          workflowPollThreadCount,
          activityPollThreadCount,
          localActivityWorkerOnly,
          defaultDeadlockDetectionTimeout);
    }

    public WorkerOptions validateAndBuildWithDefaults() {
      Preconditions.checkState(
          maxWorkerActivitiesPerSecond >= 0, "negative maxActivitiesPerSecond");
      Preconditions.checkState(
          maxConcurrentActivityExecutionSize >= 0, "negative maxConcurrentActivityExecutionSize");
      Preconditions.checkState(
          maxConcurrentWorkflowTaskExecutionSize >= 0,
          "negative maxConcurrentWorkflowTaskExecutionSize");
      Preconditions.checkState(
          maxConcurrentLocalActivityExecutionSize >= 0,
          "negative maxConcurrentLocalActivityExecutionSize");
      Preconditions.checkState(
          maxTaskQueueActivitiesPerSecond >= 0, "negative taskQueueActivitiesPerSecond");
      Preconditions.checkState(workflowPollThreadCount >= 0, "negative workflowPollThreadCount");
      Preconditions.checkState(activityPollThreadCount >= 0, "negative activityPollThreadCount");
      Preconditions.checkState(
          defaultDeadlockDetectionTimeout >= 0, "negative defaultDeadlockDetectionTimeout");
      return new WorkerOptions(
          maxWorkerActivitiesPerSecond,
          maxConcurrentActivityExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE
              : maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowTaskExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE
              : maxConcurrentWorkflowTaskExecutionSize,
          maxConcurrentLocalActivityExecutionSize == 0
              ? DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE
              : maxConcurrentLocalActivityExecutionSize,
          maxTaskQueueActivitiesPerSecond,
          workflowPollThreadCount == 0
              ? DEFAULT_WORKFLOW_POLL_THREAD_COUNT
              : workflowPollThreadCount,
          activityPollThreadCount == 0
              ? DEFAULT_ACTIVITY_POLL_THREAD_COUNT
              : activityPollThreadCount,
          localActivityWorkerOnly,
          defaultDeadlockDetectionTimeout == 0
              ? DEFAULT_DEADLOCK_DETECTION_TIMEOUT
              : defaultDeadlockDetectionTimeout);
    }
  }

  private final double maxWorkerActivitiesPerSecond;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowTaskExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double maxTaskQueueActivitiesPerSecond;
  private final int workflowPollThreadCount;
  private final int activityPollThreadCount;
  private final boolean localActivityWorkerOnly;
  private final long defaultDeadlockDetectionTimeout;

  private WorkerOptions(
      double maxWorkerActivitiesPerSecond,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double maxTaskQueueActivitiesPerSecond,
      int workflowPollThreadCount,
      int activityPollThreadCount,
      boolean localActivityWorkerOnly,
      long defaultDeadlockDetectionTimeout) {
    this.maxWorkerActivitiesPerSecond = maxWorkerActivitiesPerSecond;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.maxTaskQueueActivitiesPerSecond = maxTaskQueueActivitiesPerSecond;
    this.workflowPollThreadCount = workflowPollThreadCount;
    this.activityPollThreadCount = activityPollThreadCount;
    this.localActivityWorkerOnly = localActivityWorkerOnly;
    this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
  }

  public double getMaxWorkerActivitiesPerSecond() {
    return maxWorkerActivitiesPerSecond;
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

  public double getMaxTaskQueueActivitiesPerSecond() {
    return maxTaskQueueActivitiesPerSecond;
  }

  public int getWorkflowPollThreadCount() {
    return workflowPollThreadCount;
  }

  public int getActivityPollThreadCount() {
    return activityPollThreadCount;
  }

  public long getDefaultDeadlockDetectionTimeout() {
    return defaultDeadlockDetectionTimeout;
  }

  public boolean isLocalActivityWorkerOnly() {
    return localActivityWorkerOnly;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkerOptions that = (WorkerOptions) o;
    return compare(that.maxWorkerActivitiesPerSecond, maxWorkerActivitiesPerSecond) == 0
        && maxConcurrentActivityExecutionSize == that.maxConcurrentActivityExecutionSize
        && maxConcurrentWorkflowTaskExecutionSize == that.maxConcurrentWorkflowTaskExecutionSize
        && maxConcurrentLocalActivityExecutionSize == that.maxConcurrentLocalActivityExecutionSize
        && compare(that.maxTaskQueueActivitiesPerSecond, maxTaskQueueActivitiesPerSecond) == 0
        && workflowPollThreadCount == that.workflowPollThreadCount
        && activityPollThreadCount == that.activityPollThreadCount
        && localActivityWorkerOnly == that.localActivityWorkerOnly
        && defaultDeadlockDetectionTimeout == that.defaultDeadlockDetectionTimeout;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        maxWorkerActivitiesPerSecond,
        maxConcurrentActivityExecutionSize,
        maxConcurrentWorkflowTaskExecutionSize,
        maxConcurrentLocalActivityExecutionSize,
        maxTaskQueueActivitiesPerSecond,
        workflowPollThreadCount,
        activityPollThreadCount,
        localActivityWorkerOnly,
        defaultDeadlockDetectionTimeout);
  }

  @Override
  public String toString() {
    return "WorkerOptions{"
        + "maxWorkerActivitiesPerSecond="
        + maxWorkerActivitiesPerSecond
        + ", maxConcurrentActivityExecutionSize="
        + maxConcurrentActivityExecutionSize
        + ", maxConcurrentWorkflowTaskExecutionSize="
        + maxConcurrentWorkflowTaskExecutionSize
        + ", maxConcurrentLocalActivityExecutionSize="
        + maxConcurrentLocalActivityExecutionSize
        + ", maxTaskQueueActivitiesPerSecond="
        + maxTaskQueueActivitiesPerSecond
        + ", workflowPollThreadCount="
        + workflowPollThreadCount
        + ", activityPollThreadCount="
        + activityPollThreadCount
        + ", localActivityWorkerOnly="
        + localActivityWorkerOnly
        + ", defaultDeadlockDetectionTimeout="
        + defaultDeadlockDetectionTimeout
        + '}';
  }
}
