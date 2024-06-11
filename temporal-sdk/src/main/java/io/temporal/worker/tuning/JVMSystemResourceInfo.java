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

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

public class JVMSystemResourceInfo implements SystemResourceInfo {
  // As of relatively recent Java versions (including backports), this class will properly deal with
  // containerized environments as well as running on bare metal.
  // See https://bugs.openjdk.org/browse/JDK-8226575 for more details on which versions the fixes
  // have been backported to.
  OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
  MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

  @Override
  public double getCpuUsagePercent() {
    return osBean.getSystemCpuLoad();
  }

  @Override
  public double getMemoryUsagePercent() {
    long jvmUsedMemory =
        memoryBean.getHeapMemoryUsage().getUsed() + memoryBean.getNonHeapMemoryUsage().getUsed();
    long jvmMaxMemory = Runtime.getRuntime().maxMemory();
    long systemTotalMemory = osBean.getTotalPhysicalMemorySize();
    long systemUsedMemory = systemTotalMemory - osBean.getFreePhysicalMemorySize();

    double jvmMemoryUsagePercent = ((double) jvmUsedMemory / jvmMaxMemory);
    double systemMemoryUsagePercent = ((double) systemUsedMemory / systemTotalMemory);

    // We want to return either the JVM memory usage or the system memory usage, whichever is
    // higher, since we want to avoid OOMing either the JVM or the system.
    // TODO: Slightly worried this could thrash back and forth
    return Math.max(jvmMemoryUsagePercent, systemMemoryUsagePercent);
  }
}
