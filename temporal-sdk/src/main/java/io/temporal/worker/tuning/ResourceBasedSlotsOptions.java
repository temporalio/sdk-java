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

import com.google.common.base.Preconditions;

public class ResourceBasedSlotsOptions {

  public static ResourceBasedSlotsOptions.Builder newBuilder(
      double targetMemoryUsage, double targetCPUUsage) {
    return new ResourceBasedSlotsOptions.Builder()
        .setTargetMemoryUsage(targetMemoryUsage)
        .setTargetCPUUsage(targetCPUUsage);
  }

  public static final class Builder {
    private double targetMemoryUsage;
    private double targetCPUUsage;
    private double memoryPGain = 5;
    private double memoryIGain = 0;
    private double memoryDGain = 1;
    private double memoryOutputThreshold = 0.25;
    private double cpuPGain = 5;
    private double cpuIGain = 0;
    private double cpuDGain = 1;
    private double cpuOutputThreshold = 0.05;

    public Builder setTargetMemoryUsage(double targetMemoryUsage) {
      this.targetMemoryUsage = targetMemoryUsage;
      return this;
    }

    public Builder setTargetCPUUsage(double targetCPUUsage) {
      this.targetCPUUsage = targetCPUUsage;
      return this;
    }

    public Builder setMemoryPGain(double memoryPGain) {
      this.memoryPGain = memoryPGain;
      return this;
    }

    public Builder setMemoryIGain(double memoryIGain) {
      this.memoryIGain = memoryIGain;
      return this;
    }

    public Builder setMemoryDGain(double memoryDGain) {
      this.memoryDGain = memoryDGain;
      return this;
    }

    public Builder setMemoryOutputThreshold(double memoryOutputThreshold) {
      this.memoryOutputThreshold = memoryOutputThreshold;
      return this;
    }

    public Builder setCpuPGain(double cpuPGain) {
      this.cpuPGain = cpuPGain;
      return this;
    }

    public Builder setCpuIGain(double cpuIGain) {
      this.cpuIGain = cpuIGain;
      return this;
    }

    public Builder setCpuDGain(double cpuDGain) {
      this.cpuDGain = cpuDGain;
      return this;
    }

    public Builder setCpuOutputThreshold(double cpuOutputThreshold) {
      this.cpuOutputThreshold = cpuOutputThreshold;
      return this;
    }

    public ResourceBasedSlotsOptions build() {
      Preconditions.checkState(
          targetMemoryUsage > 0, "targetMemoryUsage must be set and greater than 0");
      Preconditions.checkState(targetCPUUsage > 0, "targetCPUUsage must be set and greater than 0");
      return new ResourceBasedSlotsOptions(this);
    }
  }

  private final double targetMemoryUsage;
  private final double targetCPUUsage;

  private final double memoryPGain;
  private final double memoryIGain;
  private final double memoryDGain;
  private final double memoryOutputThreshold;

  private final double cpuPGain;
  private final double cpuIGain;
  private final double cpuDGain;
  private final double cpuOutputThreshold;

  private ResourceBasedSlotsOptions(Builder builder) {
    this.targetMemoryUsage = builder.targetMemoryUsage;
    this.targetCPUUsage = builder.targetCPUUsage;
    this.memoryPGain = builder.memoryPGain;
    this.memoryIGain = builder.memoryIGain;
    this.memoryDGain = builder.memoryDGain;
    this.memoryOutputThreshold = builder.memoryOutputThreshold;
    this.cpuPGain = builder.cpuPGain;
    this.cpuIGain = builder.cpuIGain;
    this.cpuDGain = builder.cpuDGain;
    this.cpuOutputThreshold = builder.cpuOutputThreshold;
  }

  public double getTargetMemoryUsage() {
    return targetMemoryUsage;
  }

  public double getTargetCPUUsage() {
    return targetCPUUsage;
  }

  public double getMemoryPGain() {
    return memoryPGain;
  }

  public double getMemoryIGain() {
    return memoryIGain;
  }

  public double getMemoryDGain() {
    return memoryDGain;
  }

  public double getMemoryOutputThreshold() {
    return memoryOutputThreshold;
  }

  public double getCpuPGain() {
    return cpuPGain;
  }

  public double getCpuIGain() {
    return cpuIGain;
  }

  public double getCpuDGain() {
    return cpuDGain;
  }

  public double getCpuOutputThreshold() {
    return cpuOutputThreshold;
  }
}
