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

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.common.RetryOptions;
import io.temporal.converter.DataConverter;
import io.temporal.converter.JsonDataConverter;
import io.temporal.internal.common.Retryer;
import io.temporal.internal.metrics.NoopScope;
import java.time.Duration;
import java.util.List;

public final class SingleWorkerOptions {

  public static final class Builder {

    private String identity;
    private DataConverter dataConverter;
    private int taskExecutorThreadPoolSize = 100;
    private double taskListActivitiesPerSecond;
    private PollerOptions pollerOptions;
    /** TODO: Dynamic expiration based on activity timeout */
    private RetryOptions reportCompletionRetryOptions;

    private RetryOptions reportFailureRetryOptions;
    private Scope metricsScope;
    private boolean enableLoggingInReplay;
    private List<ContextPropagator> contextPropagators;

    public Builder() {}

    public Builder(SingleWorkerOptions options) {
      this.identity = options.getIdentity();
      this.dataConverter = options.getDataConverter();
      this.pollerOptions = options.getPollerOptions();
      this.taskListActivitiesPerSecond = options.getTaskListActivitiesPerSecond();
      this.taskExecutorThreadPoolSize = options.getTaskExecutorThreadPoolSize();
      this.reportCompletionRetryOptions = options.getReportCompletionRetryOptions();
      this.reportFailureRetryOptions = options.getReportFailureRetryOptions();
      this.metricsScope = options.getMetricsScope();
      this.enableLoggingInReplay = options.getEnableLoggingInReplay();
      this.contextPropagators = options.getContextPropagators();
    }

    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public Builder setDataConverter(DataConverter dataConverter) {
      this.dataConverter = dataConverter;
      return this;
    }

    public Builder setTaskExecutorThreadPoolSize(int taskExecutorThreadPoolSize) {
      this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
      return this;
    }

    public Builder setPollerOptions(PollerOptions pollerOptions) {
      this.pollerOptions = pollerOptions;
      return this;
    }

    public Builder setMetricsScope(Scope metricsScope) {
      this.metricsScope = metricsScope;
      return this;
    }

    public Builder setEnableLoggingInReplay(boolean enableLoggingInReplay) {
      this.enableLoggingInReplay = enableLoggingInReplay;
      return this;
    }

    public Builder setTaskListActivitiesPerSecond(double taskListActivitiesPerSecond) {
      this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
      return this;
    }

    public Builder setReportCompletionRetryOptions(RetryOptions reportCompletionRetryOptions) {
      this.reportCompletionRetryOptions = reportCompletionRetryOptions;
      return this;
    }

    public Builder setReportFailureRetryOptions(RetryOptions reportFailureRetryOptions) {
      this.reportFailureRetryOptions = reportFailureRetryOptions;
      return this;
    }

    /** Specifies the list of context propagators to use during this workflow. */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public SingleWorkerOptions build() {
      if (reportCompletionRetryOptions == null) {
        reportCompletionRetryOptions = Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS;
      }

      if (reportFailureRetryOptions == null) {
        reportFailureRetryOptions = Retryer.DEFAULT_SERVICE_OPERATION_RETRY_OPTIONS;
      }

      if (pollerOptions == null) {
        pollerOptions =
            new PollerOptions.Builder()
                .setPollBackoffInitialInterval(Duration.ofMillis(200))
                .setPollBackoffMaximumInterval(Duration.ofSeconds(20))
                .setPollThreadCount(1)
                .build();
      }

      if (dataConverter == null) {
        dataConverter = JsonDataConverter.getInstance();
      }

      if (metricsScope == null) {
        metricsScope = NoopScope.getInstance();
      }

      return new SingleWorkerOptions(
          identity,
          dataConverter,
          taskExecutorThreadPoolSize,
          taskListActivitiesPerSecond,
          pollerOptions,
          reportCompletionRetryOptions,
          reportFailureRetryOptions,
          metricsScope,
          enableLoggingInReplay,
          contextPropagators);
    }
  }

  private final String identity;
  private final DataConverter dataConverter;
  private final int taskExecutorThreadPoolSize;
  private final double taskListActivitiesPerSecond;
  private final PollerOptions pollerOptions;
  private final RetryOptions reportCompletionRetryOptions;
  private final RetryOptions reportFailureRetryOptions;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private List<ContextPropagator> contextPropagators;

  private SingleWorkerOptions(
      String identity,
      DataConverter dataConverter,
      int taskExecutorThreadPoolSize,
      double taskListActivitiesPerSecond,
      PollerOptions pollerOptions,
      RetryOptions reportCompletionRetryOptions,
      RetryOptions reportFailureRetryOptions,
      Scope metricsScope,
      boolean enableLoggingInReplay,
      List<ContextPropagator> contextPropagators) {
    this.identity = identity;
    this.dataConverter = dataConverter;
    this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    this.taskListActivitiesPerSecond = taskListActivitiesPerSecond;
    this.pollerOptions = pollerOptions;
    this.reportCompletionRetryOptions = reportCompletionRetryOptions;
    this.reportFailureRetryOptions = reportFailureRetryOptions;
    this.metricsScope = metricsScope;
    this.enableLoggingInReplay = enableLoggingInReplay;
    this.contextPropagators = contextPropagators;
  }

  public String getIdentity() {
    return identity;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  int getTaskExecutorThreadPoolSize() {
    return taskExecutorThreadPoolSize;
  }

  PollerOptions getPollerOptions() {
    return pollerOptions;
  }

  RetryOptions getReportCompletionRetryOptions() {
    return reportCompletionRetryOptions;
  }

  RetryOptions getReportFailureRetryOptions() {
    return reportFailureRetryOptions;
  }

  double getTaskListActivitiesPerSecond() {
    return taskListActivitiesPerSecond;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }
}
