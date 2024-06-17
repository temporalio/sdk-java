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

import io.temporal.common.Experimental;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Is used by {@link ResourceBasedSlotSupplier} and {@link ResourceBasedTuner} to make decisions
 * about whether slots should be handed out based on system resource usage.
 *
 * @param <RI> How this controller obtains system resource information
 */
@Experimental
public class ResourceController<RI extends SystemResourceInfo> {
  public final ResourceBasedControllerOptions options;

  private final ReentrantLock decisionLock = new ReentrantLock();
  private final PIDController memoryController;
  private final PIDController cpuController;
  private final RI systemInfoSupplier;
  private Instant lastPidRefresh = Instant.now();

  /**
   * Construct a controller with the given options. If you want to use resource-based tuning for all
   * slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceController<JVMSystemResourceInfo> newSystemInfoController(
      ResourceBasedControllerOptions options) {
    return new ResourceController<>(options, new JVMSystemResourceInfo());
  }

  /**
   * Construct a controller with the given options and system info supplier. Users should prefer
   * {@link #newSystemInfoController(ResourceBasedControllerOptions)}.
   */
  public ResourceController(ResourceBasedControllerOptions options, RI systemInfoSupplier) {
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
      double cpuUsage = systemInfoSupplier.getCpuUsagePercent();
      double memoryOutput =
          memoryController.getOutput(lastPidRefresh.getEpochSecond(), memoryUsage);
      double cpuOutput = cpuController.getOutput(lastPidRefresh.getEpochSecond(), cpuUsage);
      lastPidRefresh = Instant.now();
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
}
