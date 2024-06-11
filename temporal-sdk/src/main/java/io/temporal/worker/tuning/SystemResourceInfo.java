package io.temporal.worker.tuning;

public interface SystemResourceInfo {
  double getCpuUsagePercent();

  double getMemoryUsagePercent();
}
