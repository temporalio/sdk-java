package io.temporal.worker.tuning;

import javax.annotation.Nonnull;

/** WorkerTuners allow for the dynamic customization of some aspects of worker configuration. */
public interface WorkerTuner {
  /**
   * @return A {@link SlotSupplier} for workflow tasks.
   */
  @Nonnull
  SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier();

  /**
   * @return A {@link SlotSupplier} for activity tasks.
   */
  @Nonnull
  SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier();

  /**
   * @return A {@link SlotSupplier} for local activities.
   */
  @Nonnull
  SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier();

  /**
   * @return A {@link SlotSupplier} for nexus tasks.
   */
  @Nonnull
  SlotSupplier<NexusSlotInfo> getNexusSlotSupplier();
}
