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

import io.temporal.common.RpcRetryOptions;
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

    private double workerActivitiesPerSecond;
    private int maxConcurrentActivityExecutionSize = 100;
    private int maxConcurrentWorkflowExecutionSize = 50;
    private int maxConcurrentLocalActivityExecutionSize = 100;
    private double taskListActivitiesPerSecond = 100000;
    private RpcRetryOptions reportActivityCompletionRetryOptions;
    private RpcRetryOptions reportActivityFailureRetryOptions;
    private RpcRetryOptions reportWorkflowCompletionRetryOptions;
    private RpcRetryOptions reportWorkflowFailureRetryOptions;
    private Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory = (n) -> n;
    private boolean enableLoggingInReplay;

    private Builder() {}

    private Builder(WorkerOptions o) {
      if (o == null) {
        return;
      }
      workerActivitiesPerSecond = o.workerActivitiesPerSecond;
      maxConcurrentActivityExecutionSize = o.maxConcurrentActivityExecutionSize;
      maxConcurrentWorkflowExecutionSize = o.maxConcurrentWorkflowExecutionSize;
      maxConcurrentLocalActivityExecutionSize = o.maxConcurrentLocalActivityExecutionSize;
      taskListActivitiesPerSecond = o.taskListActivitiesPerSecond;
      reportActivityCompletionRetryOptions = o.reportActivityCompletionRetryOptions;
      reportActivityFailureRetryOptions = o.reportActivityFailureRetryOptions;
      reportWorkflowCompletionRetryOptions = o.reportWorkflowCompletionRetryOptions;
      reportWorkflowFailureRetryOptions = o.reportWorkflowFailureRetryOptions;
      interceptorFactory = o.interceptorFactory;
      enableLoggingInReplay = o.enableLoggingInReplay;
    }

    /** Maximum number of activities started per second. Default is 0 which means unlimited. */
    public Builder setWorkerActivitiesPerSecond(double workerActivitiesPerSecond) {
      if (workerActivitiesPerSecond <= 0) {
        throw new IllegalArgumentException("Negative or zero: " + workerActivitiesPerSecond);
      }
      this.workerActivitiesPerSecond = workerActivitiesPerSecond;
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

    /** Maximum number of parallely executed decision tasks. */
    public Builder setMaxConcurrentWorkflowExecutionSize(int maxConcurrentWorkflowExecutionSize) {
      if (maxConcurrentWorkflowExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentWorkflowExecutionSize);
      }
      this.maxConcurrentWorkflowExecutionSize = maxConcurrentWorkflowExecutionSize;
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

    public Builder setReportActivityCompletionRetryOptions(
        RpcRetryOptions reportActivityCompletionRetryOptions) {
      this.reportActivityCompletionRetryOptions =
          Objects.requireNonNull(reportActivityCompletionRetryOptions);
      return this;
    }

    public Builder setReportActivityFailureRetryOptions(
        RpcRetryOptions reportActivityFailureRetryOptions) {
      this.reportActivityFailureRetryOptions =
          Objects.requireNonNull(reportActivityFailureRetryOptions);
      return this;
    }

    public Builder setReportWorkflowCompletionRetryOptions(
        RpcRetryOptions reportWorkflowCompletionRetryOptions) {
      this.reportWorkflowCompletionRetryOptions =
          Objects.requireNonNull(reportWorkflowCompletionRetryOptions);
      return this;
    }

    public Builder setReportWorkflowFailureRetryOptions(
        RpcRetryOptions reportWorkflowFailureRetryOptions) {
      this.reportWorkflowFailureRetryOptions =
          Objects.requireNonNull(reportWorkflowFailureRetryOptions);
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
          workerActivitiesPerSecond,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowExecutionSize,
          maxConcurrentLocalActivityExecutionSize,
          taskListActivitiesPerSecond,
          reportActivityCompletionRetryOptions,
          reportActivityFailureRetryOptions,
          reportWorkflowCompletionRetryOptions,
          reportWorkflowFailureRetryOptions,
          interceptorFactory,
          enableLoggingInReplay);
    }
  }

  private final double workerActivitiesPerSecond;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double taskListActivitiesPerSecond;
  private final RpcRetryOptions reportActivityCompletionRetryOptions;
  private final RpcRetryOptions reportActivityFailureRetryOptions;
  private final RpcRetryOptions reportWorkflowCompletionRetryOptions;
  private final RpcRetryOptions reportWorkflowFailureRetryOptions;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;
  private final boolean enableLoggingInReplay;

  private WorkerOptions(
      double workerActivitiesPerSecond,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double taskListActivitiesPerSecond,
      RpcRetryOptions reportActivityCompletionRetryOptions,
      RpcRetryOptions reportActivityFailureRetryOptions,
      RpcRetryOptions reportWorkflowCompletionRetryOptions,
      RpcRetryOptions reportWorkflowFailureRetryOptions,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      boolean enableLoggingInReplay) {
    this.workerActivitiesPerSecond = workerActivitiesPerSecond;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
    this.reportActivityCompletionRetryOptions = reportActivityCompletionRetryOptions;
    this.reportActivityFailureRetryOptions = reportActivityFailureRetryOptions;
    this.reportWorkflowCompletionRetryOptions = reportWorkflowCompletionRetryOptions;
    this.reportWorkflowFailureRetryOptions = reportWorkflowFailureRetryOptions;
    this.interceptorFactory = interceptorFactory;
    this.enableLoggingInReplay = enableLoggingInReplay;
  }

  public double getWorkerActivitiesPerSecond() {
    return workerActivitiesPerSecond;
  }

  public int getMaxConcurrentActivityExecutionSize() {
    return maxConcurrentActivityExecutionSize;
  }

  public int getMaxConcurrentWorkflowExecutionSize() {
    return maxConcurrentWorkflowExecutionSize;
  }

  public int getMaxConcurrentLocalActivityExecutionSize() {
    return maxConcurrentLocalActivityExecutionSize;
  }

  public RpcRetryOptions getReportActivityCompletionRetryOptions() {
    return reportActivityCompletionRetryOptions;
  }

  public RpcRetryOptions getReportActivityFailureRetryOptions() {
    return reportActivityFailureRetryOptions;
  }

  public RpcRetryOptions getReportWorkflowCompletionRetryOptions() {
    return reportWorkflowCompletionRetryOptions;
  }

  public RpcRetryOptions getReportWorkflowFailureRetryOptions() {
    return reportWorkflowFailureRetryOptions;
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
        + workerActivitiesPerSecond
        + ", maxConcurrentActivityExecutionSize="
        + maxConcurrentActivityExecutionSize
        + ", maxConcurrentWorkflowExecutionSize="
        + maxConcurrentWorkflowExecutionSize
        + ", maxConcurrentLocalActivityExecutionSize="
        + maxConcurrentLocalActivityExecutionSize
        + ", taskListActivitiesPerSecond="
        + taskListActivitiesPerSecond
        + ", reportActivityCompletionRetryOptions="
        + reportActivityCompletionRetryOptions
        + ", reportActivityFailureRetryOptions="
        + reportActivityFailureRetryOptions
        + ", reportWorkflowCompletionRetryOptions="
        + reportWorkflowCompletionRetryOptions
        + ", reportWorkflowFailureRetryOptions="
        + reportWorkflowFailureRetryOptions
        + '}';
  }
}
