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

public class ResourceController<RI extends SystemResourceInfo> {
  public final ResourceBasedSlotsOptions options;

  private final PIDController memoryController;
  private final PIDController cpuController;
  private final RI systemInfoSupplier;

  public ResourceController(ResourceBasedSlotsOptions options, RI systemInfoSupplier) {
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
   * @return True if the PID controllers would increase the number of slots.
   */
  boolean pidDecision() {
    double memoryUsage = systemInfoSupplier.getMemoryUsagePercent();
    double cpuUsage = systemInfoSupplier.getCpuUsagePercent();
    // TODO: Ensure time interval is correct
    double memoryOutput = memoryController.getOutput(1, memoryUsage);
    double cpuOutput = cpuController.getOutput(1, cpuUsage);
    return memoryOutput > options.getMemoryOutputThreshold()
        && cpuOutput > options.getCpuOutputThreshold();
  }
}
