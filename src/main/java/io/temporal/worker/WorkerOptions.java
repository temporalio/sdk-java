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

import com.uber.m3.tally.Scope;
import io.temporal.converter.DataConverter;
import io.temporal.converter.JsonDataConverter;
import io.temporal.internal.metrics.NoopScope;
import io.temporal.internal.worker.PollerOptions;
import io.temporal.serviceclient.GrpcRetryOptions;
import io.temporal.workflow.WorkflowInterceptor;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.Function;

public final class WorkerOptions {

  public static final class Builder {

    private boolean disableWorkflowWorker;
    private boolean disableActivityWorker;
    private double workerActivitiesPerSecond;
    private String identity;
    private DataConverter dataConverter = JsonDataConverter.getInstance();
    private int maxConcurrentActivityExecutionSize = 100;
    private int maxConcurrentWorkflowExecutionSize = 50;
    private int maxConcurrentLocalActivityExecutionSize = 100;
    private double taskListActivitiesPerSecond = 100000;
    private PollerOptions activityPollerOptions;
    private PollerOptions workflowPollerOptions;
    private GrpcRetryOptions reportActivityCompletionRetryOptions;
    private GrpcRetryOptions reportActivityFailureRetryOptions;
    private GrpcRetryOptions reportWorkflowCompletionRetryOptions;
    private GrpcRetryOptions reportWorkflowFailureRetryOptions;
    private Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory = (n) -> n;
    private Scope metricsScope;
    private boolean enableLoggingInReplay;

    /**
     * When set to true doesn't poll on workflow task list even if there are registered workflows
     * with a worker. For clarity prefer not registing workflow types with a {@link Worker} to
     * setting this option. But it can be useful for disabling polling through configuration without
     * a code change.
     */
    public Builder setDisableWorkflowWorker(boolean disableWorkflowWorker) {
      this.disableWorkflowWorker = disableWorkflowWorker;
      return this;
    }

    /**
     * When set to true doesn't poll on activity task list even if there are registered activities
     * with a worker. For clarity prefer not registing activity implementations with a {@link
     * Worker} to setting this option. But it can be useful for disabling polling through
     * configuration without a code change.
     */
    public Builder setDisableActivityWorker(boolean disableActivityWorker) {
      this.disableActivityWorker = disableActivityWorker;
      return this;
    }

    /**
     * Override human readable identity of the worker. Identity is used to identify a worker and is
     * recorded in the workflow history events. For example when a worker gets an activity task the
     * correspondent ActivityTaskStarted event contains the worker identity as a field. Default is
     * whatever <code>(ManagementFactory.getRuntimeMXBean().getName()</code> returns.
     */
    public Builder setIdentity(String identity) {
      this.identity = Objects.requireNonNull(identity);
      return this;
    }

    /**
     * Override a data converter implementation used by workflows and activities executed by this
     * worker. Default is {@link io.temporal.converter.JsonDataConverter} data converter.
     */
    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = Objects.requireNonNull(dataConverter);
      return this;
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

    public Builder setActivityPollerOptions(PollerOptions activityPollerOptions) {
      this.activityPollerOptions = Objects.requireNonNull(activityPollerOptions);
      return this;
    }

    public Builder setWorkflowPollerOptions(PollerOptions workflowPollerOptions) {
      this.workflowPollerOptions = Objects.requireNonNull(workflowPollerOptions);
      return this;
    }

    public Builder setReportActivityCompletionRetryOptions(
        GrpcRetryOptions reportActivityCompletionRetryOptions) {
      this.reportActivityCompletionRetryOptions =
          Objects.requireNonNull(reportActivityCompletionRetryOptions);
      return this;
    }

    public Builder setReportActivityFailureRetryOptions(
        GrpcRetryOptions reportActivityFailureRetryOptions) {
      this.reportActivityFailureRetryOptions =
          Objects.requireNonNull(reportActivityFailureRetryOptions);
      return this;
    }

    public Builder setReportWorkflowCompletionRetryOptions(
        GrpcRetryOptions reportWorkflowCompletionRetryOptions) {
      this.reportWorkflowCompletionRetryOptions =
          Objects.requireNonNull(reportWorkflowCompletionRetryOptions);
      return this;
    }

    public Builder setReportWorkflowFailureRetryOptions(
        GrpcRetryOptions reportWorkflowFailureRetryOptions) {
      this.reportWorkflowFailureRetryOptions =
          Objects.requireNonNull(reportWorkflowFailureRetryOptions);
      return this;
    }

    public Builder setInterceptorFactory(
        Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory) {
      this.interceptorFactory = Objects.requireNonNull(interceptorFactory);
      return this;
    }

    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = Objects.requireNonNull(metricsScope);
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
      if (identity == null) {
        identity = ManagementFactory.getRuntimeMXBean().getName();
      }

      if (metricsScope == null) {
        metricsScope = NoopScope.getInstance();
      }

      return new WorkerOptions(
          disableWorkflowWorker,
          disableActivityWorker,
          workerActivitiesPerSecond,
          identity,
          dataConverter,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorkflowExecutionSize,
          maxConcurrentLocalActivityExecutionSize,
          taskListActivitiesPerSecond,
          activityPollerOptions,
          workflowPollerOptions,
          reportActivityCompletionRetryOptions,
          reportActivityFailureRetryOptions,
          reportWorkflowCompletionRetryOptions,
          reportWorkflowFailureRetryOptions,
          interceptorFactory,
          metricsScope,
          enableLoggingInReplay);
    }
  }

  private final boolean disableWorkflowWorker;
  private final boolean disableActivityWorker;
  private final double workerActivitiesPerSecond;
  private final String identity;
  private final DataConverter dataConverter;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorkflowExecutionSize;
  private final int maxConcurrentLocalActivityExecutionSize;
  private final double taskListActivitiesPerSecond;
  private final PollerOptions activityPollerOptions;
  private final PollerOptions workflowPollerOptions;
  private final GrpcRetryOptions reportActivityCompletionRetryOptions;
  private final GrpcRetryOptions reportActivityFailureRetryOptions;
  private final GrpcRetryOptions reportWorkflowCompletionRetryOptions;
  private final GrpcRetryOptions reportWorkflowFailureRetryOptions;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;

  private WorkerOptions(
      boolean disableWorkflowWorker,
      boolean disableActivityWorker,
      double workerActivitiesPerSecond,
      String identity,
      DataConverter dataConverter,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorkflowExecutionSize,
      int maxConcurrentLocalActivityExecutionSize,
      double taskListActivitiesPerSecond,
      PollerOptions activityPollerOptions,
      PollerOptions workflowPollerOptions,
      GrpcRetryOptions reportActivityCompletionRetryOptions,
      GrpcRetryOptions reportActivityFailureRetryOptions,
      GrpcRetryOptions reportWorkflowCompletionRetryOptions,
      GrpcRetryOptions reportWorkflowFailureRetryOptions,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory,
      Scope metricsScope,
      boolean enableLoggingInReplay) {
    this.disableWorkflowWorker = disableWorkflowWorker;
    this.disableActivityWorker = disableActivityWorker;
    this.workerActivitiesPerSecond = workerActivitiesPerSecond;
    this.identity = identity;
    this.dataConverter = dataConverter;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorkflowExecutionSize = maxConcurrentWorkflowExecutionSize;
    this.maxConcurrentLocalActivityExecutionSize = maxConcurrentLocalActivityExecutionSize;
    this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
    this.activityPollerOptions = activityPollerOptions;
    this.workflowPollerOptions = workflowPollerOptions;
    this.reportActivityCompletionRetryOptions = reportActivityCompletionRetryOptions;
    this.reportActivityFailureRetryOptions = reportActivityFailureRetryOptions;
    this.reportWorkflowCompletionRetryOptions = reportWorkflowCompletionRetryOptions;
    this.reportWorkflowFailureRetryOptions = reportWorkflowFailureRetryOptions;
    this.interceptorFactory = interceptorFactory;
    this.metricsScope = metricsScope;
    this.enableLoggingInReplay = enableLoggingInReplay;
  }

  public boolean isDisableWorkflowWorker() {
    return disableWorkflowWorker;
  }

  public boolean isDisableActivityWorker() {
    return disableActivityWorker;
  }

  public double getWorkerActivitiesPerSecond() {
    return workerActivitiesPerSecond;
  }

  public String getIdentity() {
    return identity;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
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

  public PollerOptions getActivityPollerOptions() {
    return activityPollerOptions;
  }

  public PollerOptions getWorkflowPollerOptions() {
    return workflowPollerOptions;
  }

  public GrpcRetryOptions getReportActivityCompletionRetryOptions() {
    return reportActivityCompletionRetryOptions;
  }

  public GrpcRetryOptions getReportActivityFailureRetryOptions() {
    return reportActivityFailureRetryOptions;
  }

  public GrpcRetryOptions getReportWorkflowCompletionRetryOptions() {
    return reportWorkflowCompletionRetryOptions;
  }

  public GrpcRetryOptions getReportWorkflowFailureRetryOptions() {
    return reportWorkflowFailureRetryOptions;
  }

  public Function<WorkflowInterceptor, WorkflowInterceptor> getInterceptorFactory() {
    return interceptorFactory;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  @Override
  public String toString() {
    return "WorkerOptions{"
        + "disableWorkflowWorker="
        + disableWorkflowWorker
        + ", disableActivityWorker="
        + disableActivityWorker
        + ", workerActivitiesPerSecond="
        + workerActivitiesPerSecond
        + ", identity='"
        + identity
        + '\''
        + ", dataConverter="
        + dataConverter
        + ", maxConcurrentActivityExecutionSize="
        + maxConcurrentActivityExecutionSize
        + ", maxConcurrentWorkflowExecutionSize="
        + maxConcurrentWorkflowExecutionSize
        + ", maxConcurrentLocalActivityExecutionSize="
        + maxConcurrentLocalActivityExecutionSize
        + ", taskListActivitiesPerSecond="
        + taskListActivitiesPerSecond
        + ", activityPollerOptions="
        + activityPollerOptions
        + ", workflowPollerOptions="
        + workflowPollerOptions
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
