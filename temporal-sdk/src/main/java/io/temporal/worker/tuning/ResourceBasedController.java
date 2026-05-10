package io.temporal.worker.tuning;

import com.uber.m3.tally.Gauge;
import com.uber.m3.tally.Scope;
import io.temporal.worker.MetricsType;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Is used by {@link ResourceBasedSlotSupplier} and {@link ResourceBasedTuner} to make decisions
 * about whether slots should be handed out based on system resource usage.
 */
public class ResourceBasedController {
  public final ResourceBasedControllerOptions options;

  private final ReentrantLock decisionLock = new ReentrantLock();
  private final PIDController memoryController;
  private final PIDController cpuController;
  private final SystemResourceInfo systemInfoSupplier;
  private Instant lastPidRefresh = Instant.now();

  private final AtomicReference<Metrics> metrics = new AtomicReference<>();

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
            options.getTargetMemoryUsage(),
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

      Metrics metrics = this.metrics.get();
      if (metrics != null) {
        metrics.memUsage.update(memoryUsage * 100);
        metrics.cpuUsage.update(cpuUsage * 100);
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
