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
    private String binaryChecksum;
    private DataConverter dataConverter;
    private int taskExecutorThreadPoolSize = 100;
    private PollerOptions pollerOptions;
    private Scope metricsScope;
    private boolean enableLoggingInReplay;
    private List<ContextPropagator> contextPropagators;
    private long defaultDeadlockDetectionTimeout;
    private Duration maxHeartbeatThrottleInterval;
    private Duration defaultHeartbeatThrottleInterval;

    private Builder() {}

    private Builder(SingleWorkerOptions options) {
      if (options == null) {
        return;
      }
      this.identity = options.getIdentity();
      this.binaryChecksum = options.getBinaryChecksum();
      this.dataConverter = options.getDataConverter();
      this.pollerOptions = options.getPollerOptions();
      this.taskExecutorThreadPoolSize = options.getTaskExecutorThreadPoolSize();
      this.metricsScope = options.getMetricsScope();
      this.enableLoggingInReplay = options.getEnableLoggingInReplay();
      this.contextPropagators = options.getContextPropagators();
      this.defaultDeadlockDetectionTimeout = options.getDefaultDeadlockDetectionTimeout();
      this.maxHeartbeatThrottleInterval = options.getMaxHeartbeatThrottleInterval();
      this.defaultHeartbeatThrottleInterval = options.getDefaultHeartbeatThrottleInterval();
    }

    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    public Builder setBinaryChecksum(String binaryChecksum) {
      this.binaryChecksum = binaryChecksum;
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

    public Builder setDefaultDeadlockDetectionTimeout(long defaultDeadlockDetectionTimeout) {
      this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
      return this;
    }

    public Builder setMaxHeartbeatThrottleInterval(Duration maxHeartbeatThrottleInterval) {
      this.maxHeartbeatThrottleInterval = maxHeartbeatThrottleInterval;
      return this;
    }

    public Builder setDefaultHeartbeatThrottleInterval(Duration defaultHeartbeatThrottleInterval) {
      this.defaultHeartbeatThrottleInterval = defaultHeartbeatThrottleInterval;
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
          binaryChecksum,
          dataConverter,
          taskExecutorThreadPoolSize,
          pollerOptions,
          metricsScope,
          enableLoggingInReplay,
          contextPropagators,
          defaultDeadlockDetectionTimeout,
          maxHeartbeatThrottleInterval,
          defaultHeartbeatThrottleInterval);
    }
  }

  private final String identity;
  private final String binaryChecksum;
  private final DataConverter dataConverter;
  private final int taskExecutorThreadPoolSize;
  private final PollerOptions pollerOptions;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final List<ContextPropagator> contextPropagators;
  private final long defaultDeadlockDetectionTimeout;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;

  private SingleWorkerOptions(
      String identity,
      String binaryChecksum,
      DataConverter dataConverter,
      int taskExecutorThreadPoolSize,
      PollerOptions pollerOptions,
      Scope metricsScope,
      boolean enableLoggingInReplay,
      List<ContextPropagator> contextPropagators,
      long defaultDeadlockDetectionTimeout,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    this.identity = identity;
    this.binaryChecksum = binaryChecksum;
    this.dataConverter = dataConverter;
    this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    this.pollerOptions = pollerOptions;
    this.metricsScope = metricsScope;
    this.enableLoggingInReplay = enableLoggingInReplay;
    this.contextPropagators = contextPropagators;
    this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
    this.maxHeartbeatThrottleInterval = maxHeartbeatThrottleInterval;
    this.defaultHeartbeatThrottleInterval = defaultHeartbeatThrottleInterval;
  }

  public String getIdentity() {
    return identity;
  }

  public String getBinaryChecksum() {
    return binaryChecksum;
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

  public long getDefaultDeadlockDetectionTimeout() {
    return defaultDeadlockDetectionTimeout;
  }

  public Duration getMaxHeartbeatThrottleInterval() {
    return maxHeartbeatThrottleInterval;
  }

  public Duration getDefaultHeartbeatThrottleInterval() {
    return defaultHeartbeatThrottleInterval;
  }
}
