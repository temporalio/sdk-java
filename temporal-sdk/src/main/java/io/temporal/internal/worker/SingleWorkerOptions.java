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

package io.temporal.internal.worker;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkerVersionStamp;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
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
    private String buildID;
    private boolean useBuildIDForVersioning;
    private DataConverter dataConverter;
    private int taskExecutorThreadPoolSize = 100;
    private PollerOptions pollerOptions;
    private Scope metricsScope;
    private boolean enableLoggingInReplay;
    private List<ContextPropagator> contextPropagators;
    private WorkerInterceptor[] workerInterceptors;

    private Duration stickyQueueScheduleToStartTimeout;
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
      this.workerInterceptors = options.getWorkerInterceptors();
      this.stickyQueueScheduleToStartTimeout = options.getStickyQueueScheduleToStartTimeout();
      this.defaultDeadlockDetectionTimeout = options.getDefaultDeadlockDetectionTimeout();
      this.maxHeartbeatThrottleInterval = options.getMaxHeartbeatThrottleInterval();
      this.defaultHeartbeatThrottleInterval = options.getDefaultHeartbeatThrottleInterval();
      this.buildID = options.getBuildID();
      this.useBuildIDForVersioning = options.isUsingBuildIDForVersioning();
    }

    public Builder setIdentity(String identity) {
      this.identity = identity;
      return this;
    }

    @Deprecated
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

    /** Specifies the list of worker interceptors to use during this workflow. */
    public Builder setWorkerInterceptors(WorkerInterceptor[] workerInterceptors) {
      this.workerInterceptors = workerInterceptors;
      return this;
    }

    public Builder setStickyQueueScheduleToStartTimeout(
        Duration stickyQueueScheduleToStartTimeout) {
      this.stickyQueueScheduleToStartTimeout = stickyQueueScheduleToStartTimeout;
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

    public Builder setBuildID(String buildID) {
      this.buildID = buildID;
      return this;
    }

    public Builder setUseBuildIDForVersioning(boolean useBuildIDForVersioning) {
      this.useBuildIDForVersioning = useBuildIDForVersioning;
      return this;
    }

    public SingleWorkerOptions build() {
      PollerOptions pollerOptions = this.pollerOptions;
      if (pollerOptions == null) {
        pollerOptions = PollerOptions.newBuilder().build();
      }

      DataConverter dataConverter = this.dataConverter;
      if (dataConverter == null) {
        dataConverter = GlobalDataConverter.get();
      }

      Scope metricsScope = this.metricsScope;
      if (metricsScope == null) {
        metricsScope = new NoopScope();
      }

      return new SingleWorkerOptions(
          this.identity,
          this.binaryChecksum,
          this.buildID,
          this.useBuildIDForVersioning,
          dataConverter,
          this.taskExecutorThreadPoolSize,
          pollerOptions,
          metricsScope,
          this.enableLoggingInReplay,
          this.contextPropagators,
          this.workerInterceptors,
          this.stickyQueueScheduleToStartTimeout,
          this.defaultDeadlockDetectionTimeout,
          this.maxHeartbeatThrottleInterval,
          this.defaultHeartbeatThrottleInterval);
    }
  }

  private final String identity;
  private final String binaryChecksum;
  private final String buildID;
  private final boolean useBuildIDForVersioning;
  private final DataConverter dataConverter;
  private final int taskExecutorThreadPoolSize;
  private final PollerOptions pollerOptions;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final List<ContextPropagator> contextPropagators;
  private final WorkerInterceptor[] workerInterceptors;
  private final Duration stickyQueueScheduleToStartTimeout;
  private final long defaultDeadlockDetectionTimeout;
  private final Duration maxHeartbeatThrottleInterval;
  private final Duration defaultHeartbeatThrottleInterval;

  private SingleWorkerOptions(
      String identity,
      String binaryChecksum,
      String buildID,
      boolean useBuildIDForVersioning,
      DataConverter dataConverter,
      int taskExecutorThreadPoolSize,
      PollerOptions pollerOptions,
      Scope metricsScope,
      boolean enableLoggingInReplay,
      List<ContextPropagator> contextPropagators,
      WorkerInterceptor[] workerInterceptors,
      Duration stickyQueueScheduleToStartTimeout,
      long defaultDeadlockDetectionTimeout,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    this.identity = identity;
    this.binaryChecksum = binaryChecksum;
    this.buildID = buildID;
    this.useBuildIDForVersioning = useBuildIDForVersioning;
    this.dataConverter = dataConverter;
    this.taskExecutorThreadPoolSize = taskExecutorThreadPoolSize;
    this.pollerOptions = pollerOptions;
    this.metricsScope = metricsScope;
    this.enableLoggingInReplay = enableLoggingInReplay;
    this.contextPropagators = contextPropagators;
    this.workerInterceptors = workerInterceptors;
    this.stickyQueueScheduleToStartTimeout = stickyQueueScheduleToStartTimeout;
    this.defaultDeadlockDetectionTimeout = defaultDeadlockDetectionTimeout;
    this.maxHeartbeatThrottleInterval = maxHeartbeatThrottleInterval;
    this.defaultHeartbeatThrottleInterval = defaultHeartbeatThrottleInterval;
  }

  public String getIdentity() {
    return identity;
  }

  @Deprecated
  public String getBinaryChecksum() {
    return binaryChecksum;
  }

  public String getBuildID() {
    if (buildID == null) {
      return binaryChecksum;
    }
    return buildID;
  }

  public boolean isUsingBuildIDForVersioning() {
    return useBuildIDForVersioning;
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

  public Scope getMetricsScope() {
    return metricsScope;
  }

  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
  }

  public WorkerInterceptor[] getWorkerInterceptors() {
    return workerInterceptors;
  }

  public Duration getStickyQueueScheduleToStartTimeout() {
    return stickyQueueScheduleToStartTimeout;
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

  public WorkerVersionStamp workerVersionStamp() {
    return WorkerVersionStamp.newBuilder()
        .setBuildId(this.getBuildID())
        .setUseVersioning(this.isUsingBuildIDForVersioning())
        .build();
  }
}
