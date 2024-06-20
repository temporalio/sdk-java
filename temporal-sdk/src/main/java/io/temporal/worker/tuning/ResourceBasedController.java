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

package io.temporal.worker.tuning;

import com.uber.m3.tally.Gauge;
import com.uber.m3.tally.Scope;
import io.temporal.common.Experimental;
import io.temporal.worker.MetricsType;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Is used by {@link ResourceBasedSlotSupplier} and {@link ResourceBasedTuner} to make decisions
 * about whether slots should be handed out based on system resource usage.
 */
@Experimental
public class ResourceBasedController {
  public final ResourceBasedControllerOptions options;

  private final ReentrantLock decisionLock = new ReentrantLock();
  private final PIDController memoryController;
  private final PIDController cpuController;
  private final SystemResourceInfo systemInfoSupplier;
  private Instant lastPidRefresh = Instant.now();

  private AtomicReference<Metrics> metrics = new AtomicReference<>();
  private boolean metricsScopeSet;
  private volatile double lastMemUsage;
  private volatile double lastCpuUsage;
  private volatile double lastMemPidOut;
  private volatile double lastCpuPidOut;

  /**
   * Construct a controller with the given options. If you want to use resource-based tuning for all
   * slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedController newSystemInfoController(
      ResourceBasedControllerOptions options) {
    return new ResourceBasedController(options, new JVMSystemResourceInfo());
  }

  /**
   * Construct a controller with the given options and system info supplier. Users should prefer
   * {@link #newSystemInfoController(ResourceBasedControllerOptions)}.
   */
  public ResourceBasedController(
      ResourceBasedControllerOptions options, SystemResourceInfo systemInfoSupplier) {
    this.options = options;
    this.systemInfoSupplier = systemInfoSupplier;
    this.memoryController =
        new PIDController(
            options.getTargetCPUUsage(),
            options.getMemoryPGain(),
            options.getMemoryIGain(),
            options.getMemoryDGain());
    this.cpuController =
        new PIDController(
            options.getTargetCPUUsage(),
            options.getCpuPGain(),
            options.getCpuIGain(),
            options.getCpuDGain());
  }

  /**
   * @return True if the PID controllers & and other constraints would allow another slot
   */
  boolean pidDecision() {
    decisionLock.lock();
    try {
      double memoryUsage = systemInfoSupplier.getMemoryUsagePercent();
      double cpuUsage = systemInfoSupplier.getCPUUsagePercent();
      double memoryOutput =
          memoryController.getOutput(lastPidRefresh.getEpochSecond(), memoryUsage);
      double cpuOutput = cpuController.getOutput(lastPidRefresh.getEpochSecond(), cpuUsage);
      lastPidRefresh = Instant.now();
      lastMemUsage = memoryUsage;
      lastCpuUsage = cpuUsage;
      lastMemPidOut = memoryOutput;
      lastCpuPidOut = cpuOutput;

      Metrics metrics = this.metrics.get();
      if (metrics != null) {
        metrics.memUsage.update(memoryUsage);
        metrics.cpuUsage.update(cpuUsage);
        metrics.memPidOut.update(memoryOutput);
        metrics.cpuPidOut.update(cpuOutput);
      }

      return memoryOutput > options.getMemoryOutputThreshold()
          && cpuOutput > options.getCpuOutputThreshold()
          && canReserve();
    } finally {
      decisionLock.unlock();
    }
  }

  private boolean canReserve() {
    return systemInfoSupplier.getMemoryUsagePercent() < options.getTargetMemoryUsage();
  }

  /** Visible for internal usage. Can only be set once. */
  public void setMetricsScope(Scope metricsScope) {
    if (metrics.get() == null) {
      metrics.set(new Metrics(metricsScope));
    }
  }

  private static class Metrics {
    private final Gauge memUsage;
    private final Gauge cpuUsage;
    private final Gauge memPidOut;
    private final Gauge cpuPidOut;

    private Metrics(Scope scope) {
      memUsage = scope.gauge(MetricsType.RESOURCE_MEM_USAGE);
      cpuUsage = scope.gauge(MetricsType.RESOURCE_CPU_USAGE);
      memPidOut = scope.gauge(MetricsType.RESOURCE_MEM_PID);
      cpuPidOut = scope.gauge(MetricsType.RESOURCE_CPU_PID);
    }
  }
}
