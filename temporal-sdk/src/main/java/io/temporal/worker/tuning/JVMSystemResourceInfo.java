package io.temporal.worker.tuning;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** {@link SystemResourceInfo} implementation that uses JVM-specific APIs to get resource usage. */
public class JVMSystemResourceInfo implements SystemResourceInfo {
  // As of relatively recent Java versions (including backports), this class will properly deal with
  // containerized environments as well as running on bare metal.
  // See https://bugs.openjdk.org/browse/JDK-8226575 for more details on which versions the fixes
  // have been backported to.
  OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

  private final Lock refreshLock = new ReentrantLock();
  private SystemInfo lastSystemInfo;

  @Override
  public double getCPUUsagePercent() {
    return refresh().cpuUsagePercent;
  }

  @Override
  public double getMemoryUsagePercent() {
    return refresh().memoryUsagePercent;
  }

  @SuppressWarnings("deprecation") // deprecated APIs needed since replacements are for Java 14+
  private SystemInfo refresh() {

    refreshLock.lock();
    try {
      if (lastSystemInfo == null
          || Instant.now().isAfter(lastSystemInfo.refreshed.plusMillis(100))) {
        // This can return NaN seemingly when usage is very low
        double lastCpuUsage = osBean.getSystemCpuLoad();
        if (lastCpuUsage < 0 || Double.isNaN(lastCpuUsage)) {
          lastCpuUsage = 0;
        }

        Runtime runtime = Runtime.getRuntime();
        long jvmUsedMemory = runtime.totalMemory() - runtime.freeMemory();
        long jvmMaxMemory = runtime.maxMemory();

        double lastMemUsage = ((double) jvmUsedMemory / jvmMaxMemory);
        Instant lastRefresh = Instant.now();
        lastSystemInfo = new SystemInfo(lastRefresh, lastCpuUsage, lastMemUsage);
      }
    } finally {
      refreshLock.unlock();
    }

    return lastSystemInfo;
  }

  private static class SystemInfo {
    private final Instant refreshed;
    private final double cpuUsagePercent;
    private final double memoryUsagePercent;

    private SystemInfo(Instant refreshed, double cpuUsagePercent, double memoryUsagePercent) {
      this.refreshed = refreshed;
      this.cpuUsagePercent = cpuUsagePercent;
      this.memoryUsagePercent = memoryUsagePercent;
    }
  }
}
