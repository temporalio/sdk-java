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
            options.getTargetCPUUsage(), options.getCpuPGain(), options.getCpuIGain(), options.getCpuDGain());
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
    return memoryOutput > options.getMemoryOutputThreshold() && cpuOutput > options.getCpuOutputThreshold();
  }
}
