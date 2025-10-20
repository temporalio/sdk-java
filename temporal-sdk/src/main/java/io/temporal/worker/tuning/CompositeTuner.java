package io.temporal.worker.tuning;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Can be used to create a {@link WorkerTuner} which uses specific {@link SlotSupplier}s for each
 * type of slot.
 */
public class CompositeTuner implements WorkerTuner {
  private final @Nonnull SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier;
  private final @Nonnull SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier;
  private final @Nonnull SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier;
  private final @Nonnull SlotSupplier<NexusSlotInfo> nexusSlotSupplier;

  public CompositeTuner(
      @Nonnull SlotSupplier<WorkflowSlotInfo> workflowTaskSlotSupplier,
      @Nonnull SlotSupplier<ActivitySlotInfo> activityTaskSlotSupplier,
      @Nonnull SlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier,
      @Nonnull SlotSupplier<NexusSlotInfo> nexusSlotSupplier) {
    this.workflowTaskSlotSupplier = Objects.requireNonNull(workflowTaskSlotSupplier);
    this.activityTaskSlotSupplier = Objects.requireNonNull(activityTaskSlotSupplier);
    this.localActivitySlotSupplier = Objects.requireNonNull(localActivitySlotSupplier);
    this.nexusSlotSupplier = Objects.requireNonNull(nexusSlotSupplier);

    // All resource-based slot suppliers must use the same controller
    validateResourceController(workflowTaskSlotSupplier, activityTaskSlotSupplier);
    validateResourceController(workflowTaskSlotSupplier, localActivitySlotSupplier);
    validateResourceController(activityTaskSlotSupplier, localActivitySlotSupplier);
    validateResourceController(workflowTaskSlotSupplier, nexusSlotSupplier);
  }

  @Nonnull
  @Override
  public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
    return workflowTaskSlotSupplier;
  }

  @Nonnull
  @Override
  public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
    return activityTaskSlotSupplier;
  }

  @Nonnull
  @Override
  public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
    return localActivitySlotSupplier;
  }

  @Nonnull
  @Override
  public SlotSupplier<NexusSlotInfo> getNexusSlotSupplier() {
    return nexusSlotSupplier;
  }

  private <T extends SlotInfo, U extends SlotInfo> void validateResourceController(
      @Nonnull SlotSupplier<T> supplier1, @Nonnull SlotSupplier<U> supplier2) {
    if (supplier1 instanceof ResourceBasedSlotSupplier
        && supplier2 instanceof ResourceBasedSlotSupplier) {
      ResourceBasedController controller1 =
          ((ResourceBasedSlotSupplier<?>) supplier1).getResourceController();
      ResourceBasedController controller2 =
          ((ResourceBasedSlotSupplier<?>) supplier2).getResourceController();
      if (controller1 != controller2) {
        throw new IllegalArgumentException(
            "All resource-based slot suppliers must use the same ResourceController");
      }
    }
  }
}
