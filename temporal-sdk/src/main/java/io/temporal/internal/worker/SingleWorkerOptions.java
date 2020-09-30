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

package io.temporal.internal.worker;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import java.time.Duration;
import java.util.List;

public final class SingleWorkerOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(SingleWorkerOptions options) {
    return new Builder(options);
  }

  public static final class Builder {

    private String identity;
    private DataConverter dataConverter;
    private int taskExecutorThreadPoolSize = 100;
    private PollerOptions pollerOptions;
    private Scope metricsScope;
    private boolean enableLoggingInReplay;
    private List<ContextPropagator> contextPropagators;

    private Builder() {}

    private Builder(SingleWorkerOptions options) {
      if (options == null) {
        return;
      }
      this.identity = options.getIdentity();
      this.dataConverter = options.getDataConverter();
      this.pollerOptions = options.getPollerOptions();
      this.taskExecutorThreadPoolSize = options.getTaskExecutorThreadPoolSize();
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

    /** Specifies the list of context propagators to use during this workflow. */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
      return this;
    }

    public SingleWorkerOptions build() {
      if (pollerOptions == null) {
        pollerOptions =
            PollerOptions.newBuilder()
                .setPollBackoffInitialInterval(Duration.ofMillis(200))
                .setPollBackoffMaximumInterval(Duration.ofSeconds(20))
                .setPollThreadCount(1)
                .build();
      }

      if (dataConverter == null) {
        dataConverter = DataConverter.getDefaultInstance();
      }

      if (metricsScope == null) {
        metricsScope = new NoopScope();
      }

      return new SingleWorkerOptions(
          identity,
          dataConverter,
          taskExecutorThreadPoolSize,
          pollerOptions,
          metricsScope,
          enableLoggingInReplay,
          contextPropagators);
    }
  }

  private final String identity;
  private final DataConverter dataConverter;
  private final int taskExecutorThreadPoolSize;
  private final PollerOptions pollerOptions;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final List<ContextPropagator> contextPropagators;

  private SingleWorkerOptions(
      String identity,
      DataConverter dataConverter,
      int taskExecutorThreadPoolSize,
      PollerOptions pollerOptions,
      Scope metricsScope,
      boolean enableLoggingInReplay,
      List<ContextPropagator> contextPropagators) {
    this.identity = identity;
    this.dataConverter = dataConverter;
    this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    this.pollerOptions = pollerOptions;
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
