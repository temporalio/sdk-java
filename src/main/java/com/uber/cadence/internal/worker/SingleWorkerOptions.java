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

package com.uber.cadence.internal.worker;

import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.m3.tally.Scope;
import java.time.Duration;

public final class SingleWorkerOptions {

  public static final class Builder {

    private String identity;

    private DataConverter dataConverter;

    private int taskExecutorThreadPoolSize = 100;

    private PollerOptions pollerOptions;

    /** TODO: Dynamic expiration based on activity timeout */
    private RetryOptions reportCompletionRetryOptions;

    private RetryOptions reportFailureRetryOptions;

    private Scope metricsScope;

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

    public SingleWorkerOptions build() {
      if (reportCompletionRetryOptions == null) {
        reportCompletionRetryOptions =
            new RetryOptions.Builder()
                .setInitialInterval(Duration.ofMillis(50))
                .setMaximumInterval(Duration.ofSeconds(10))
                .setExpiration(Duration.ofMinutes(1))
                .build();
      }

      if (reportFailureRetryOptions == null) {
        reportFailureRetryOptions =
            new RetryOptions.Builder()
                .setInitialInterval(Duration.ofMillis(50))
                .setMaximumInterval(Duration.ofSeconds(10))
                .setExpiration(Duration.ofMinutes(1))
                .build();
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
          pollerOptions,
          reportCompletionRetryOptions,
          reportFailureRetryOptions,
          metricsScope);
    }

    public Builder setReportCompletionRetryOptions(RetryOptions reportCompletionRetryOptions) {
      this.reportCompletionRetryOptions = reportCompletionRetryOptions;
      return this;
    }

    public Builder setReportFailureRetryOptions(RetryOptions reportFailureRetryOptions) {
      this.reportFailureRetryOptions = reportFailureRetryOptions;
      return this;
    }
  }

  private final String identity;

  private final DataConverter dataConverter;

  private final int taskExecutorThreadPoolSize;

  private final PollerOptions pollerOptions;

  private final RetryOptions reportCompletionRetryOptions;

  private final RetryOptions reportFailureRetryOptions;

  private final Scope metricsScope;

  private SingleWorkerOptions(
      String identity,
      DataConverter dataConverter,
      int taskExecutorThreadPoolSize,
      PollerOptions pollerOptions,
      RetryOptions reportCompletionRetryOptions,
      RetryOptions reportFailureRetryOptions,
      Scope metricsScope) {
    this.identity = identity;
    this.dataConverter = dataConverter;
    this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    this.pollerOptions = pollerOptions;
    this.reportCompletionRetryOptions = reportCompletionRetryOptions;
    this.reportFailureRetryOptions = reportFailureRetryOptions;
    this.metricsScope = metricsScope;
  }

  public String getIdentity() {
    return identity;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public int getTaskExecutorThreadPoolSize() {
    return taskExecutorThreadPoolSize;
  }

  public PollerOptions getPollerOptions() {
    return pollerOptions;
  }

  public RetryOptions getReportCompletionRetryOptions() {
    return reportCompletionRetryOptions;
  }

  public RetryOptions getReportFailureRetryOptions() {
    return reportFailureRetryOptions;
  }

  public Scope getMetricsScope() {
    return metricsScope;
  }
}
