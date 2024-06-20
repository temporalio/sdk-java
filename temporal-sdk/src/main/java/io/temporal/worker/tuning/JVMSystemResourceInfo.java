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
import io.temporal.common.Experimental;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/** {@link SystemResourceInfo} implementation that uses JVM-specific APIs to get resource usage. */
@Experimental
public class JVMSystemResourceInfo implements SystemResourceInfo {
  // As of relatively recent Java versions (including backports), this class will properly deal with
  // containerized environments as well as running on bare metal.
  // See https://bugs.openjdk.org/browse/JDK-8226575 for more details on which versions the fixes
  // have been backported to.
  OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

  private final ReentrantLock refreshLock = new ReentrantLock();
  volatile Instant lastRefresh = Instant.EPOCH;
  volatile double lastMemUsage = Double.NaN;
  volatile double lastCpuUsage = Double.NaN;

  @Override
  public double getCPUUsagePercent() {
    refresh();
    return lastCpuUsage;
  }

  @Override
  public double getMemoryUsagePercent() {
    refresh();
    return lastMemUsage;
  }

  @SuppressWarnings("deprecation") // deprecated APIs needed since replacements are for Java 14+
  private void refresh() {
    if (Instant.now().isBefore(lastRefresh.plusMillis(100))) {
      return;
    }
    refreshLock.lock();

    try {
      // This can return NaN seemingly when usage is very low
      lastCpuUsage = osBean.getSystemCpuLoad();
      if (lastCpuUsage < 0 || Double.isNaN(lastCpuUsage)) {
        lastCpuUsage = 0;
      }

      Runtime runtime = Runtime.getRuntime();
      long jvmUsedMemory = runtime.totalMemory() - runtime.freeMemory();
      long jvmMaxMemory = runtime.maxMemory();

      lastMemUsage = ((double) jvmUsedMemory / jvmMaxMemory);
      lastRefresh = Instant.now();
    } finally {
      refreshLock.unlock();
    }
  }
}
