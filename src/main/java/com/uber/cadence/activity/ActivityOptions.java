/*
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

package com.uber.cadence.activity;

import static com.uber.cadence.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.cadence.common.MethodRetry;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.context.ContextPropagator;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Options used to configure how an activity is invoked. */
public final class ActivityOptions {

  /**
   * Used to merge annotation and options. Options takes precedence. Returns options with all
   * defaults filled in.
   */
  public static ActivityOptions merge(ActivityMethod a, MethodRetry r, ActivityOptions o) {
    if (a == null) {
      if (r == null) {
        return new ActivityOptions.Builder(o).validateAndBuildWithDefaults();
      }
      RetryOptions mergedR = RetryOptions.merge(r, o.getRetryOptions());
      return new ActivityOptions.Builder().setRetryOptions(mergedR).validateAndBuildWithDefaults();
    }
    if (o == null) {
      o = new ActivityOptions.Builder().build();
    }
    return new ActivityOptions.Builder()
        .setScheduleToCloseTimeout(
            mergeDuration(a.scheduleToCloseTimeoutSeconds(), o.getScheduleToCloseTimeout()))
        .setScheduleToStartTimeout(
            mergeDuration(a.scheduleToStartTimeoutSeconds(), o.getScheduleToStartTimeout()))
        .setStartToCloseTimeout(
            mergeDuration(a.startToCloseTimeoutSeconds(), o.getStartToCloseTimeout()))
        .setHeartbeatTimeout(mergeDuration(a.heartbeatTimeoutSeconds(), o.getHeartbeatTimeout()))
        .setTaskList(
            o.getTaskList() != null
                ? o.getTaskList()
                : (a.taskList().isEmpty() ? null : a.taskList()))
        .setRetryOptions(RetryOptions.merge(r, o.getRetryOptions()))
        .setContextPropagators(o.getContextPropagators())
        .validateAndBuildWithDefaults();
  }

  public static final class Builder {

    private Duration heartbeatTimeout;

    private Duration scheduleToCloseTimeout;

    private Duration scheduleToStartTimeout;

    private Duration startToCloseTimeout;

    private String taskList;

    private RetryOptions retryOptions;

    private List<ContextPropagator> contextPropagators;

    public Builder() {}

    /** Copy Builder fields from the options. */
    public Builder(ActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToStartTimeout = options.getScheduleToStartTimeout();
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.heartbeatTimeout = options.getHeartbeatTimeout();
      this.startToCloseTimeout = options.getStartToCloseTimeout();
      this.taskList = options.taskList;
      this.retryOptions = options.retryOptions;
      this.contextPropagators = options.contextPropagators;
    }

    /**
     * Overall timeout workflow is willing to wait for activity to complete. It includes time in a
     * task list (use {@link #setScheduleToStartTimeout(Duration)} to limit it) plus activity
     * execution time (use {@link #setStartToCloseTimeout(Duration)} to limit it). Either this
     * option or both schedule to start and start to close are required.
     */
    public Builder setScheduleToCloseTimeout(Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /**
     * Time activity can stay in task list before it is picked up by a worker. If schedule to close
     * is not provided then both this and start to close are required.
     */
    public Builder setScheduleToStartTimeout(Duration scheduleToStartTimeout) {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      return this;
    }

    /**
     * Maximum activity execution time after it was sent to a worker. If schedule to close is not
     * provided then both this and schedule to start are required.
     */
    public Builder setStartToCloseTimeout(Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /**
     * Heartbeat interval. Activity must heartbeat before this interval passes after a last
     * heartbeat or activity start.
     */
    public Builder setHeartbeatTimeout(Duration heartbeatTimeoutSeconds) {
      this.heartbeatTimeout = heartbeatTimeoutSeconds;
      return this;
    }

    /**
     * Task list to use when dispatching activity task to a worker. By default it is the same task
     * list name the workflow was started with.
     */
    public Builder setTaskList(String taskList) {
      this.taskList = taskList;
      return this;
    }

    /**
     * RetryOptions that define how activity is retried in case of failure. Default is null which is
     * no reties.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /** ContextPropagators help propagate the context from the workflow to the activities */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public ActivityOptions build() {
      return new ActivityOptions(
          heartbeatTimeout,
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          startToCloseTimeout,
          taskList,
          retryOptions,
          contextPropagators);
    }

    public ActivityOptions validateAndBuildWithDefaults() {
      if (scheduleToCloseTimeout == null
          && (scheduleToStartTimeout == null || startToCloseTimeout == null)) {
        throw new IllegalStateException(
            "Either ScheduleToClose or both ScheduleToStart and StartToClose "
                + "timeouts are required: ");
      }
      Duration scheduleToClose = scheduleToCloseTimeout;
      if (scheduleToClose == null) {
        scheduleToClose = scheduleToStartTimeout.plus(startToCloseTimeout);
      }
      Duration startToClose = startToCloseTimeout;
      if (startToClose == null) {
        startToClose = scheduleToCloseTimeout;
      }
      Duration scheduleToStart = scheduleToStartTimeout;
      if (scheduleToStartTimeout == null) {
        scheduleToStart = scheduleToClose;
      }
      // Cadence still requires it.
      Duration heartbeat = heartbeatTimeout;
      if (heartbeatTimeout == null) {
        heartbeat = scheduleToClose;
      }
      RetryOptions ro = null;
      if (retryOptions != null) {
        ro = new RetryOptions.Builder(retryOptions).validateBuildWithDefaults();
      }
      return new ActivityOptions(
          roundUpToSeconds(heartbeat),
          roundUpToSeconds(scheduleToClose),
          roundUpToSeconds(scheduleToStart),
          roundUpToSeconds(startToClose),
          taskList,
          ro,
          contextPropagators);
    }
  }

  private final Duration heartbeatTimeout;

  private final Duration scheduleToCloseTimeout;

  private final Duration scheduleToStartTimeout;

  private final Duration startToCloseTimeout;

  private final String taskList;

  private final RetryOptions retryOptions;

  private final List<ContextPropagator> contextPropagators;

  private ActivityOptions(
      Duration heartbeatTimeout,
      Duration scheduleToCloseTimeout,
      Duration scheduleToStartTimeout,
      Duration startToCloseTimeout,
      String taskList,
      RetryOptions retryOptions,
      List<ContextPropagator> contextPropagators) {
    this.heartbeatTimeout = heartbeatTimeout;
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    if (scheduleToCloseTimeout != null) {
      if (scheduleToStartTimeout == null) {
        this.scheduleToStartTimeout = scheduleToCloseTimeout;
      } else {
        this.scheduleToStartTimeout = scheduleToStartTimeout;
      }
      if (startToCloseTimeout == null) {
        this.startToCloseTimeout = scheduleToCloseTimeout;
      } else {
        this.startToCloseTimeout = startToCloseTimeout;
      }
    } else {
      this.scheduleToStartTimeout = scheduleToStartTimeout;
      this.startToCloseTimeout = startToCloseTimeout;
    }
    this.taskList = taskList;
    this.retryOptions = retryOptions;
    this.contextPropagators = contextPropagators;
  }

  public Duration getHeartbeatTimeout() {
    return heartbeatTimeout;
  }

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  public String getTaskList() {
    return taskList;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  @Override
  public String toString() {
    return "ActivityOptions{"
        + "heartbeatTimeout="
        + heartbeatTimeout
        + ", scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", scheduleToStartTimeout="
        + scheduleToStartTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", taskList='"
        + taskList
        + '\''
        + ", retryOptions="
        + retryOptions
        + ", contextPropagators"
        + contextPropagators
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ActivityOptions that = (ActivityOptions) o;
    return Objects.equals(heartbeatTimeout, that.heartbeatTimeout)
        && Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equals(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equals(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equals(taskList, that.taskList)
        && Objects.equals(retryOptions, that.retryOptions)
        && Objects.equals(contextPropagators, that.contextPropagators);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        heartbeatTimeout,
        scheduleToCloseTimeout,
        scheduleToStartTimeout,
        startToCloseTimeout,
        taskList,
        retryOptions,
        contextPropagators);
  }

  static Duration mergeDuration(int annotationSeconds, Duration options) {
    if (options == null) {
      if (annotationSeconds == 0) {
        return null;
      }
      return Duration.ofSeconds(annotationSeconds);
    } else {
      return options;
    }
  }
}
