package io.temporal.worker.tuning;

/** Implementors determine how resource usage is measured. */
public interface SystemResourceInfo {
  /**
   * @return System-wide CPU usage as a percentage [0.0, 1.0]
   */
  double getCPUUsagePercent();

  /**
   * @return Memory usage as a percentage [0.0, 1.0]. Memory usage should reflect either system-wide
   *     usage or JVM-specific usage, whichever is higher, to avoid running out of memory in either
   *     way.
   */
  double getMemoryUsagePercent();
}
