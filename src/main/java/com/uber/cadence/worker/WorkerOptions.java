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

package com.uber.cadence.worker;

import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.worker.PollerOptions;
import com.uber.cadence.workflow.WorkflowInterceptor;
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
    private int maxConcurrentWorklfowExecutionSize = 50;
    private int maxWorkflowThreads = 200;
    private PollerOptions activityPollerOptions;
    private PollerOptions workflowPollerOptions;
    private RetryOptions reportActivityCompletionRetryOptions;
    private RetryOptions reportActivityFailureRetryOptions;
    private RetryOptions reportWorkflowCompletionRetryOptions;
    private RetryOptions reportWorkflowFailureRetryOptions;
    private Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory = (n) -> n;

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
     * worker. Default is {@link com.uber.cadence.converter.JsonDataConverter} data converter.
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
    public Builder setMaxConcurrentWorklfowExecutionSize(int maxConcurrentWorklfowExecutionSize) {
      if (maxConcurrentWorklfowExecutionSize <= 0) {
        throw new IllegalArgumentException(
            "Negative or zero: " + maxConcurrentWorklfowExecutionSize);
      }
      this.maxConcurrentWorklfowExecutionSize = maxConcurrentWorklfowExecutionSize;
      return this;
    }

    /** Maximum size of a thread pool used by workflow threads. */
    public Builder setMaxWorkflowThreads(int maxWorkflowThreads) {
      if (maxWorkflowThreads <= 0) {
        throw new IllegalArgumentException("Negative or zero: " + maxWorkflowThreads);
      }
      this.maxWorkflowThreads = maxWorkflowThreads;
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
        RetryOptions reportActivityCompletionRetryOptions) {
      this.reportActivityCompletionRetryOptions =
          Objects.requireNonNull(reportActivityCompletionRetryOptions);
      return this;
    }

    public Builder setReportActivityFailureRetryOptions(
        RetryOptions reportActivityFailureRetryOptions) {
      this.reportActivityFailureRetryOptions =
          Objects.requireNonNull(reportActivityFailureRetryOptions);
      return this;
    }

    public Builder setReportWorkflowCompletionRetryOptions(
        RetryOptions reportWorkflowCompletionRetryOptions) {
      this.reportWorkflowCompletionRetryOptions =
          Objects.requireNonNull(reportWorkflowCompletionRetryOptions);
      return this;
    }

    public Builder setReportWorkflowFailureRetryOptions(
        RetryOptions reportWorkflowFailureRetryOptions) {
      this.reportWorkflowFailureRetryOptions =
          Objects.requireNonNull(reportWorkflowFailureRetryOptions);
      return this;
    }

    public Builder setInterceptorFactory(
        Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory) {
      this.interceptorFactory = Objects.requireNonNull(interceptorFactory);
      return this;
    }

    public WorkerOptions build() {
      if (identity == null) {
        identity = ManagementFactory.getRuntimeMXBean().getName();
      }
      return new WorkerOptions(
          disableWorkflowWorker,
          disableActivityWorker,
          workerActivitiesPerSecond,
          identity,
          dataConverter,
          maxConcurrentActivityExecutionSize,
          maxConcurrentWorklfowExecutionSize,
          maxWorkflowThreads,
          activityPollerOptions,
          workflowPollerOptions,
          reportActivityCompletionRetryOptions,
          reportActivityFailureRetryOptions,
          reportWorkflowCompletionRetryOptions,
          reportWorkflowFailureRetryOptions,
          interceptorFactory);
    }
  }

  private final boolean disableWorkflowWorker;
  private final boolean disableActivityWorker;
  private final double workerActivitiesPerSecond;
  private final String identity;
  private final DataConverter dataConverter;
  private final int maxConcurrentActivityExecutionSize;
  private final int maxConcurrentWorklfowExecutionSize;
  private final int maxWorkflowThreads;
  private final PollerOptions activityPollerOptions;
  private final PollerOptions workflowPollerOptions;
  private final RetryOptions reportActivityCompletionRetryOptions;
  private final RetryOptions reportActivityFailureRetryOptions;
  private final RetryOptions reportWorkflowCompletionRetryOptions;
  private final RetryOptions reportWorkflowFailureRetryOptions;
  private final Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory;

  private WorkerOptions(
      boolean disableWorkflowWorker,
      boolean disableActivityWorker,
      double workerActivitiesPerSecond,
      String identity,
      DataConverter dataConverter,
      int maxConcurrentActivityExecutionSize,
      int maxConcurrentWorklfowExecutionSize,
      int maxWorkflowThreads,
      PollerOptions activityPollerOptions,
      PollerOptions workflowPollerOptions,
      RetryOptions reportActivityCompletionRetryOptions,
      RetryOptions reportActivityFailureRetryOptions,
      RetryOptions reportWorkflowCompletionRetryOptions,
      RetryOptions reportWorkflowFailureRetryOptions,
      Function<WorkflowInterceptor, WorkflowInterceptor> interceptorFactory) {
    this.disableWorkflowWorker = disableWorkflowWorker;
    this.disableActivityWorker = disableActivityWorker;
    this.workerActivitiesPerSecond = workerActivitiesPerSecond;
    this.identity = identity;
    this.dataConverter = dataConverter;
    this.maxConcurrentActivityExecutionSize = maxConcurrentActivityExecutionSize;
    this.maxConcurrentWorklfowExecutionSize = maxConcurrentWorklfowExecutionSize;
    this.maxWorkflowThreads = maxWorkflowThreads;
    this.activityPollerOptions = activityPollerOptions;
    this.workflowPollerOptions = workflowPollerOptions;
    this.reportActivityCompletionRetryOptions = reportActivityCompletionRetryOptions;
    this.reportActivityFailureRetryOptions = reportActivityFailureRetryOptions;
    this.reportWorkflowCompletionRetryOptions = reportWorkflowCompletionRetryOptions;
    this.reportWorkflowFailureRetryOptions = reportWorkflowFailureRetryOptions;
    this.interceptorFactory = interceptorFactory;
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

  public int getMaxConcurrentWorklfowExecutionSize() {
    return maxConcurrentWorklfowExecutionSize;
  }

  public int getMaxWorkflowThreads() {
    return maxWorkflowThreads;
  }

  public PollerOptions getActivityPollerOptions() {
    return activityPollerOptions;
  }

  public PollerOptions getWorkflowPollerOptions() {
    return workflowPollerOptions;
  }

  public RetryOptions getReportActivityCompletionRetryOptions() {
    return reportActivityCompletionRetryOptions;
  }

  public RetryOptions getReportActivityFailureRetryOptions() {
    return reportActivityFailureRetryOptions;
  }

  public RetryOptions getReportWorkflowCompletionRetryOptions() {
    return reportWorkflowCompletionRetryOptions;
  }

  public RetryOptions getReportWorkflowFailureRetryOptions() {
    return reportWorkflowFailureRetryOptions;
  }

  public Function<WorkflowInterceptor, WorkflowInterceptor> getInterceptorFactory() {
    return interceptorFactory;
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
        + ", maxWorkflowThreads="
        + maxWorkflowThreads
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
