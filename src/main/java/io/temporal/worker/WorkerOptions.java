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

package io.temporal.worker;

import io.temporal.workflow.WorkflowInterceptor;
import java.util.Objects;
import java.util.function.Function;

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

    private double maxActivitiesPerSecond;
    private int maxConcurrentActivityExecutionSize = 100;
    private int maxConcurrentWorkflowTaskExecutionSize = 50;
    private int maxConcurrentLocalActivityExecutionSize = 100;
    private double taskListActivitiesPerSecond = 100000;
    private Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory = (n) -> n;
    private boolean enableLoggingInReplay;

    private Builder() {}

    private Builder(WorkerOptions o) {
      if (o == null) {
        return;
      }
      maxActivitiesPerSecond = o.maxActivitiesPerSecond;
      maxConcurrentActivityExecutionSize = o.maxConcurrentActivityExecutionSize;
      maxConcurrentWorkflowTaskExecutionSize = o.maxConcurrentWorkflowTaskExecutionSize;
      maxConcurrentLocalActivityExecutionSize = o.maxConcurrentLocalActivityExecutionSize;
      taskListActivitiesPerSecond = o.taskListActivitiesPerSecond;
      interceptorFactory = o.interceptorFactory;
      enableLoggingInReplay = o.enableLoggingInReplay;
    }

    /**
     * Maximum number of activities started per second by this worker. Default is 0 which means
     * unlimited.
     *
     * <p>Note that this is a per worker limit. Use {@link #setTaskListActivitiesPerSecond(double)}
     * to set per task list limit across multiple workers.
     */
    public Builder setMaxActivitiesPerSecond(double maxActivitiesPerSecond) {
      if (maxActivitiesPerSecond <= 0) {
        throw new IllegalArgumentException("Negative or zero: " + maxActivitiesPerSecond);
      }
      this.maxActivitiesPerSecond = maxActivitiesPerSecond;
      return this;
    }

    /** Maximum number of parallely executed activities. */
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

    /** Maximum number of parallely executed local activities. */
    public Builder setMaxConcurrentLocalActivityExecutionSize(
        int maxConcurrentLocalActivityExecutionSize) {
      if (maxConcurrentLocalActivityExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentLocalActivityExecutionSize);
      }
      this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
      return this;
    }

    public Builder setInterceptorFactory(
        Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory) {
      this.interceptorFactory = Objects.requireNonNull(interceptorFactory);
      return this;
    }

    public Builder setEnableLoggingInReplay(boolean enableLoggingInReplay) {
      this.enableLoggingInReplay = enableLoggingInReplay;
      return this;
    }

    /**
     * Optional: Sets the rate limiting on number of activities that can be executed per second.
     * This is managed by the server and controls activities per second for your entire tasklist.
     * Notice that the number is represented in double, so that you can set it to less than 1 if
     * needed. For example, set the number to 0.1 means you want your activity to be executed once
     * every 10 seconds. This can be used to protect down stream services from flooding. The zero
     * value of this uses the default value. Default: 100k
     */
    public Builder setTaskListActivitiesPerSecond(double taskListActivitiesPerSecond) {
      this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
      return this;
    }

    public WorkerOptions build() {
      return new WorkerOptions(
          maxActivitiesPerSecond,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowTaskExecutionSize,
          maxConcurrentLocalActivityExecutionSize,
          taskListActivitiesPerSecond,
          interceptorFactory,
          enableLoggingInReplay);
    }
  }

  private final double maxActivitiesPerSecond;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowTaskExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double taskListActivitiesPerSecond;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;
  private final boolean enableLoggingInReplay;

  private WorkerOptions(
      double maxActivitiesPerSecond,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double taskListActivitiesPerSecond,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      boolean enableLoggingInReplay) {
    this.maxActivitiesPerSecond = maxActivitiesPerSecond;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowTaskExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
    this.interceptorFactory = interceptorFactory;
    this.enableLoggingInReplay = enableLoggingInReplay;
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

  public Function<WorkflowInterceptor, WorkflowInterceptor> getInterceptorFactory() {
    return interceptorFactory;
  }

  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  @Override
  public String toString() {
    return "WorkerOptions{"
        + ", workerActivitiesPerSecond="
        + maxActivitiesPerSecond
        + ", maxConcurrentActivityExecutionSize="
        + maxConcurrentActivityExecutionSize
        + ", maxConcurrentWorkflowExecutionSize="
        + maxConcurrentWorkflowTaskExecutionSize
        + ", maxConcurrentLocalActivityExecutionSize="
        + maxConcurrentLocalActivityExecutionSize
        + ", taskListActivitiesPerSecond="
        + taskListActivitiesPerSecond
        + '}';
  }
}
