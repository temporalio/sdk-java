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

import static java.lang.Double.compare;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

  static final Duration DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT = Duration.ofSeconds(5);

  private static final WorkerOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkerOptions.newBuilder().validateAndBuildWithDefaults();
  }

  public static final class Builder {

    private static final int DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS = 5;
    private static final int DEFAULT_MAX_CONCURRENT_ACTIVITY_TASK_POLLERS = 5;
    private static final int DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 200;
    private static final int DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 200;
    private static final int DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 200;
    private static final long DEFAULT_DEADLOCK_DETECTION_TIMEOUT = 1000;
    private static final Duration DEFAULT_MAX_HEARTBEAT_THROTTLE_INTERVAL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_DEFAULT_HEARTBEAT_THROTTLE_INTERVAL =
        Duration.ofSeconds(30);

    private double maxWorkerActivitiesPerSecond;
    private int maxConcurrentActivityExecutionSize;
    private int maxConcurrentWorkflowTaskExecutionSize;
    private int maxConcurrentLocalActivityExecutionSize;
    private double maxTaskQueueActivitiesPerSecond;
    private int maxConcurrentWorkflowTaskPollers;
    private int maxConcurrentActivityTaskPollers;
    private boolean localActivityWorkerOnly;
    private long defaultDeadlockDetectionTimeout;
    private Duration maxHeartbeatThrottleInterval;
    private Duration defaultHeartbeatThrottleInterval;
    private Duration stickyQueueScheduleToStartTimeout;
    private boolean disableEagerExecution;
    private String buildId;
    private boolean useBuildIdForVersioning;

    private Builder() {}

    private Builder(WorkerOptions o) {
      if (o == null) {
        return;
      }
      this.maxWorkerActivitiesPerSecond = o.maxWorkerActivitiesPerSecond;
      this.maxConcurrentActivityExecutionSize = o.maxConcurrentActivityExecutionSize;
      this.maxConcurrentWorkflowTaskExecutionSize = o.maxConcurrentWorkflowTaskExecutionSize;
      this.maxConcurrentLocalActivityExecutionSize = o.maxConcurrentLocalActivityExecutionSize;
      this.maxTaskQueueActivitiesPerSecond = o.maxTaskQueueActivitiesPerSecond;
      this.maxConcurrentWorkflowTaskPollers = o.maxConcurrentWorkflowTaskPollers;
      this.maxConcurrentActivityTaskPollers = o.maxConcurrentActivityTaskPollers;
      this.localActivityWorkerOnly = o.localActivityWorkerOnly;
      this.defaultDeadlockDetectionTimeout = o.defaultDeadlockDetectionTimeout;
      this.maxHeartbeatThrottleInterval = o.maxHeartbeatThrottleInterval;
      this.defaultHeartbeatThrottleInterval = o.defaultHeartbeatThrottleInterval;
      this.stickyQueueScheduleToStartTimeout = o.stickyQueueScheduleToStartTimeout;
      this.disableEagerExecution = o.disableEagerExecution;
      this.useBuildIdForVersioning = o.useBuildIdForVersioning;
      this.buildId = o.buildId;
    }

    /**
     * @param maxWorkerActivitiesPerSecond Maximum number of activities started per second by this
     *     worker. Default is 0 which means unlimited.
     * @return {@code this}
     *     <p>If worker is not fully loaded while tasks are backing up on the service consider
     *     increasing {@link #setMaxConcurrentActivityTaskPollers(int)}.
     *     <p>Note that this is a per worker limit. Use {@link
     *     #setMaxTaskQueueActivitiesPerSecond(double)} to set per task queue limit across multiple
     *     workers.
     */
    public Builder setMaxWorkerActivitiesPerSecond(double maxWorkerActivitiesPerSecond) {
      if (maxWorkerActivitiesPerSecond < 0) {
        throw new IllegalArgumentException(
            "Negative maxWorkerActivitiesPerSecond value: " + maxWorkerActivitiesPerSecond);
      }
      this.maxWorkerActivitiesPerSecond = maxWorkerActivitiesPerSecond;
      return this;
    }

    /**
     * @param maxConcurrentActivityExecutionSize Maximum number of activities executed in parallel.
     *     Default is 200, which is chosen if set to zero.
     * @return {@code this}
     */
    public Builder setMaxConcurrentActivityExecutionSize(int maxConcurrentActivityExecutionSize) {
      if (maxConcurrentActivityExecutionSize < 0) {
        throw new IllegalArgumentException(
            "Negative maxConcurrentActivityExecutionSize value: "
                + maxConcurrentActivityExecutionSize);
      }
      this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
      return this;
    }

    /**
     * @param maxConcurrentWorkflowTaskExecutionSize Maximum number of simultaneously executed
     *     workflow tasks. Default is 200, which is chosen if set to zero.
     * @return {@code this}
     *     <p>Note that this is not related to the total number of open workflows which do not need
     *     to be loaded in a worker when they are not making state transitions.
     */
    public Builder setMaxConcurrentWorkflowTaskExecutionSize(
        int maxConcurrentWorkflowTaskExecutionSize) {
      if (maxConcurrentWorkflowTaskExecutionSize < 0) {
        throw new IllegalArgumentException(
            "Negative maxConcurrentWorkflowTaskExecutionSize value: "
                + maxConcurrentWorkflowTaskExecutionSize);
      }
      this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowTaskExecutionSize;
      return this;
    }

    /**
     * @param maxConcurrentLocalActivityExecutionSize Maximum number of local activities executed in
     *     parallel. Default is 200, which is chosen if set to zero.
     * @return {@code this}
     */
    public Builder setMaxConcurrentLocalActivityExecutionSize(
        int maxConcurrentLocalActivityExecutionSize) {
      if (maxConcurrentLocalActivityExecutionSize < 0) {
        throw new IllegalArgumentException(
            "Negative maxConcurrentLocalActivityExecutionSize value: "
                + maxConcurrentLocalActivityExecutionSize);
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
     * Sets the maximum number of simultaneous long poll requests to the Temporal Server to retrieve
     * workflow tasks. Changing this value will affect the rate at which the worker is able to
     * consume tasks from a task queue.
     *
     * <p>Due to internal logic where pollers alternate between sticky and non-sticky queues, this
     * value cannot be 1 and will be adjusted to 2 if set to that value.
     *
     * <p>Default is 5.
     */
    public Builder setMaxConcurrentWorkflowTaskPollers(int maxConcurrentWorkflowTaskPollers) {
      this.maxConcurrentWorkflowTaskPollers = maxConcurrentWorkflowTaskPollers;
      return this;
    }

    /**
     * Number of simultaneous poll requests on workflow task queue. Note that the majority of the
     * workflow tasks will be using host local task queue due to caching. So try incrementing {@link
     * WorkerFactoryOptions.Builder#setWorkflowHostLocalPollThreadCount(int)} before this one.
     *
     * <p>Default is 5.
     *
     * @deprecated Use {@link #setMaxConcurrentWorkflowTaskPollers}
     */
    @Deprecated
    public Builder setWorkflowPollThreadCount(int workflowPollThreadCount) {
      return setMaxConcurrentWorkflowTaskPollers(workflowPollThreadCount);
    }

    /**
     * Number of simultaneous poll requests on activity task queue. Consider incrementing if the
     * worker is not throttled due to `MaxActivitiesPerSecond` or
     * `MaxConcurrentActivityExecutionSize` options and still cannot keep up with the request rate.
     *
     * <p>Default is 5.
     */
    public Builder setMaxConcurrentActivityTaskPollers(int maxConcurrentActivityTaskPollers) {
      this.maxConcurrentActivityTaskPollers = maxConcurrentActivityTaskPollers;
      return this;
    }

    /**
     * Number of simultaneous poll requests on activity task queue. Consider incrementing if the
     * worker is not throttled due to `MaxActivitiesPerSecond` or
     * `MaxConcurrentActivityExecutionSize` options and still cannot keep up with the request rate.
     *
     * <p>Default is 5.
     *
     * @deprecated Use {@link #setMaxConcurrentActivityTaskPollers}
     */
    @Deprecated
    public Builder setActivityPollThreadCount(int activityPollThreadCount) {
      return setMaxConcurrentActivityTaskPollers(activityPollThreadCount);
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

    /**
     * @param defaultDeadlockDetectionTimeoutMs time period in ms that will be used to detect
     *     workflows deadlock. Default is 1000ms, which is chosen if set to zero.
     *     <p>Specifies an amount of time in milliseconds that workflow tasks are allowed to execute
     *     without interruption. If workflow task runs longer than specified interval without
     *     yielding (like calling an Activity), it will fail automatically.
     * @return {@code this}
     * @see io.temporal.internal.sync.PotentialDeadlockException
     */
    public Builder setDefaultDeadlockDetectionTimeout(long defaultDeadlockDetectionTimeoutMs) {
      if (defaultDeadlockDetectionTimeoutMs < 0) {
        throw new IllegalArgumentException(
            "Negative defaultDeadlockDetectionTimeout value: " + defaultDeadlockDetectionTimeoutMs);
      }
      this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeoutMs;
      return this;
    }

    /**
     * @param interval the maximum amount of time between sending each pending heartbeat to the
     *     server. Regardless of heartbeat timeout, no pending heartbeat will wait longer than this
     *     amount of time to send. Default is 60s, which is chosen if set to null or 0.
     * @return {@code this}
     */
    public Builder setMaxHeartbeatThrottleInterval(@Nullable Duration interval) {
      Preconditions.checkArgument(
          interval == null || !interval.isNegative(),
          "Negative maxHeartbeatThrottleInterval value: %s",
          interval);
      this.maxHeartbeatThrottleInterval = interval;
      return this;
    }

    /**
     * @param interval the default amount of time between sending each pending heartbeat to the
     *     server. This is used if the ActivityOptions do not provide a HeartbeatTimeout. Otherwise,
     *     the interval becomes a value a bit smaller than the given HeartbeatTimeout. Default is
     *     30s, which is chosen if set to null or 0.
     * @return {@code this}
     */
    public Builder setDefaultHeartbeatThrottleInterval(@Nullable Duration interval) {
      Preconditions.checkArgument(
          interval == null || !interval.isNegative(),
          "Negative defaultHeartbeatThrottleInterval value: %s",
          interval);
      this.defaultHeartbeatThrottleInterval = interval;
      return this;
    }

    /**
     * Timeout for a workflow task routed to the "sticky worker" - host that has the workflow
     * instance cached in memory. Once it times out, then it can be picked up by any worker.
     *
     * <p>Default value is 5 seconds.
     */
    public Builder setStickyQueueScheduleToStartTimeout(Duration timeout) {
      this.stickyQueueScheduleToStartTimeout = timeout;
      return this;
    }

    /**
     * Disable eager activities. If set to true, eager execution will not be requested for
     * activities requested from workflows bound to this Worker.
     *
     * <p>Eager activity execution means the server returns requested eager activities directly from
     * the workflow task back to this worker which is faster than non-eager which may be dispatched
     * to a separate worker.
     *
     * <p>Defaults to false, meaning that eager activity execution is permitted
     */
    public Builder setDisableEagerExecution(boolean disableEagerExecution) {
      this.disableEagerExecution = disableEagerExecution;
      return this;
    }

    /**
     * Opts the worker in to the Build-ID-based versioning feature. This ensures that the worker
     * will only receive tasks which it is compatible with. For more information see: TODO: Doc link
     *
     * <p>Defaults to false
     */
    @Experimental
    public Builder setUseBuildIdForVersioning(boolean useBuildIdForVersioning) {
      this.useBuildIdForVersioning = useBuildIdForVersioning;
      return this;
    }

    /**
     * Set a unique identifier for this worker. The identifier should be stable with respect to the
     * code the worker uses for workflows, activities, and interceptors. For more information see:
     * TODO: Doc link
     *
     * <p>A Build Id must be set if {@link #setUseBuildIdForVersioning(boolean)} is set true.
     */
    @Experimental
    public Builder setBuildId(String buildId) {
      this.buildId = buildId;
      return this;
    }

    public WorkerOptions build() {
      return new WorkerOptions(
          maxWorkerActivitiesPerSecond,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowTaskExecutionSize,
          maxConcurrentLocalActivityExecutionSize,
          maxTaskQueueActivitiesPerSecond,
          maxConcurrentWorkflowTaskPollers,
          maxConcurrentActivityTaskPollers,
          localActivityWorkerOnly,
          defaultDeadlockDetectionTimeout,
          maxHeartbeatThrottleInterval,
          defaultHeartbeatThrottleInterval,
          stickyQueueScheduleToStartTimeout,
          disableEagerExecution,
          useBuildIdForVersioning,
          buildId);
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
      Preconditions.checkState(
          maxConcurrentWorkflowTaskPollers >= 0, "negative maxConcurrentWorkflowTaskPollers");
      Preconditions.checkState(
          maxConcurrentActivityTaskPollers >= 0, "negative maxConcurrentActivityTaskPollers");
      Preconditions.checkState(
          defaultDeadlockDetectionTimeout >= 0, "negative defaultDeadlockDetectionTimeout");
      Preconditions.checkState(
          stickyQueueScheduleToStartTimeout == null
              || !stickyQueueScheduleToStartTimeout.isNegative(),
          "negative stickyQueueScheduleToStartTimeout");
      if (useBuildIdForVersioning) {
        Preconditions.checkState(
            buildId != null && !buildId.isEmpty(),
            "buildId must be set non-empty if useBuildIdForVersioning is set true");
      }

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
          maxConcurrentWorkflowTaskPollers == 0
              ? DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS
              : maxConcurrentWorkflowTaskPollers,
          maxConcurrentActivityTaskPollers == 0
              ? DEFAULT_MAX_CONCURRENT_ACTIVITY_TASK_POLLERS
              : maxConcurrentActivityTaskPollers,
          localActivityWorkerOnly,
          defaultDeadlockDetectionTimeout == 0
              ? DEFAULT_DEADLOCK_DETECTION_TIMEOUT
              : defaultDeadlockDetectionTimeout,
          maxHeartbeatThrottleInterval == null || maxHeartbeatThrottleInterval.isZero()
              ? DEFAULT_MAX_HEARTBEAT_THROTTLE_INTERVAL
              : maxHeartbeatThrottleInterval,
          defaultHeartbeatThrottleInterval == null || defaultHeartbeatThrottleInterval.isZero()
              ? DEFAULT_DEFAULT_HEARTBEAT_THROTTLE_INTERVAL
              : defaultHeartbeatThrottleInterval,
          stickyQueueScheduleToStartTimeout == null
              ? DEFAULT_STICKY_SCHEDULE_TO_START_TIMEOUT
              : stickyQueueScheduleToStartTimeout,
          disableEagerExecution,
          useBuildIdForVersioning,
          buildId);
    }
  }

  private final double maxWorkerActivitiesPerSecond;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowTaskExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double maxTaskQueueActivitiesPerSecond;
  private final int maxConcurrentWorkflowTaskPollers;
  private final int maxConcurrentActivityTaskPollers;
  private final boolean localActivityWorkerOnly;
  private final long defaultDeadlockDetectionTimeout;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;
  private final @Nonnull Duration stickyQueueScheduleToStartTimeout;
  private final boolean disableEagerExecution;
  private final boolean useBuildIdForVersioning;
  private final String buildId;

  private WorkerOptions(
      double maxWorkerActivitiesPerSecond,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double maxTaskQueueActivitiesPerSecond,
      int workflowPollThreadCount,
      int activityPollThreadCount,
      boolean localActivityWorkerOnly,
      long defaultDeadlockDetectionTimeout,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      @Nonnull Duration stickyQueueScheduleToStartTimeout,
      boolean disableEagerExecution,
      boolean useBuildIdForVersioning,
      String buildId) {
    this.maxWorkerActivitiesPerSecond = maxWorkerActivitiesPerSecond;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.maxTaskQueueActivitiesPerSecond = maxTaskQueueActivitiesPerSecond;
    this.maxConcurrentWorkflowTaskPollers = workflowPollThreadCount;
    this.maxConcurrentActivityTaskPollers = activityPollThreadCount;
    this.localActivityWorkerOnly = localActivityWorkerOnly;
    this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
    this.maxHeartbeatThrottleInterval = maxHeartbeatThrottleInterval;
    this.defaultHeartbeatThrottleInterval = defaultHeartbeatThrottleInterval;
    this.stickyQueueScheduleToStartTimeout = stickyQueueScheduleToStartTimeout;
    this.disableEagerExecution = disableEagerExecution;
    this.useBuildIdForVersioning = useBuildIdForVersioning;
    this.buildId = buildId;
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

  /**
   * @deprecated use {@link #getMaxConcurrentWorkflowTaskPollers}
   */
  @Deprecated
  public int getWorkflowPollThreadCount() {
    return getMaxConcurrentWorkflowTaskPollers();
  }

  public int getMaxConcurrentWorkflowTaskPollers() {
    return maxConcurrentWorkflowTaskPollers;
  }

  /**
   * @deprecated use {@link #getMaxConcurrentActivityTaskPollers}
   */
  @Deprecated
  public int getActivityPollThreadCount() {
    return getMaxConcurrentActivityTaskPollers();
  }

  public int getMaxConcurrentActivityTaskPollers() {
    return maxConcurrentActivityTaskPollers;
  }

  public long getDefaultDeadlockDetectionTimeout() {
    return defaultDeadlockDetectionTimeout;
  }

  public boolean isLocalActivityWorkerOnly() {
    return localActivityWorkerOnly;
  }

  public Duration getMaxHeartbeatThrottleInterval() {
    return maxHeartbeatThrottleInterval;
  }

  public Duration getDefaultHeartbeatThrottleInterval() {
    return defaultHeartbeatThrottleInterval;
  }

  @Nonnull
  public Duration getStickyQueueScheduleToStartTimeout() {
    return stickyQueueScheduleToStartTimeout;
  }

  public boolean isEagerExecutionDisabled() {
    return disableEagerExecution;
  }

  public boolean isUsingBuildIdForVersioning() {
    return useBuildIdForVersioning;
  }

  public String getBuildId() {
    return buildId;
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
        && maxConcurrentWorkflowTaskPollers == that.maxConcurrentWorkflowTaskPollers
        && maxConcurrentActivityTaskPollers == that.maxConcurrentActivityTaskPollers
        && localActivityWorkerOnly == that.localActivityWorkerOnly
        && defaultDeadlockDetectionTimeout == that.defaultDeadlockDetectionTimeout
        && Objects.equals(maxHeartbeatThrottleInterval, that.maxHeartbeatThrottleInterval)
        && Objects.equals(defaultHeartbeatThrottleInterval, that.defaultHeartbeatThrottleInterval)
        && Objects.equals(stickyQueueScheduleToStartTimeout, that.stickyQueueScheduleToStartTimeout)
        && disableEagerExecution == that.disableEagerExecution
        && useBuildIdForVersioning == that.useBuildIdForVersioning
        && buildId.equals(that.buildId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        maxWorkerActivitiesPerSecond,
        maxConcurrentActivityExecutionSize,
        maxConcurrentWorkflowTaskExecutionSize,
        maxConcurrentLocalActivityExecutionSize,
        maxTaskQueueActivitiesPerSecond,
        maxConcurrentWorkflowTaskPollers,
        maxConcurrentActivityTaskPollers,
        localActivityWorkerOnly,
        defaultDeadlockDetectionTimeout,
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval,
        stickyQueueScheduleToStartTimeout,
        disableEagerExecution,
        useBuildIdForVersioning,
        buildId);
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
        + ", maxConcurrentWorkflowTaskPollers="
        + maxConcurrentWorkflowTaskPollers
        + ", maxConcurrentActivityTaskPollers="
        + maxConcurrentActivityTaskPollers
        + ", localActivityWorkerOnly="
        + localActivityWorkerOnly
        + ", defaultDeadlockDetectionTimeout="
        + defaultDeadlockDetectionTimeout
        + ", maxHeartbeatThrottleInterval="
        + maxHeartbeatThrottleInterval
        + ", defaultHeartbeatThrottleInterval="
        + defaultHeartbeatThrottleInterval
        + ", stickyQueueScheduleToStartTimeout="
        + stickyQueueScheduleToStartTimeout
        + ", disableEagerExecution="
        + disableEagerExecution
        + ", useBuildIdForVersioning="
        + useBuildIdForVersioning
        + ", buildId='"
        + buildId
        + '}';
  }
}
