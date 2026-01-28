package io.temporal.worker.tuning;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nonnull;

/** A {@link WorkerTuner} that attempts to allocate slots based on available system resources. */
public class ResourceBasedTuner implements WorkerTuner {
  public static final ResourceBasedSlotOptions DEFAULT_WORKFLOW_SLOT_OPTIONS =
      ResourceBasedSlotOptions.newBuilder()
          .setMinimumSlots(5)
          .setMaximumSlots(500)
          .setRampThrottle(Duration.ZERO)
          .build();
  public static final ResourceBasedSlotOptions DEFAULT_ACTIVITY_SLOT_OPTIONS =
      ResourceBasedSlotOptions.newBuilder()
          .setMinimumSlots(1)
          .setMaximumSlots(1000)
          .setRampThrottle(Duration.ofMillis(50))
          .build();
  public static final ResourceBasedSlotOptions DEFAULT_NEXUS_SLOT_OPTIONS =
      ResourceBasedSlotOptions.newBuilder()
          .setMinimumSlots(1)
          .setMaximumSlots(1000)
          .setRampThrottle(Duration.ofMillis(50))
          .build();

  private final ResourceBasedController controller;
  private final ResourceBasedSlotOptions workflowSlotOptions;
  private final ResourceBasedSlotOptions activitySlotOptions;
  private final ResourceBasedSlotOptions localActivitySlotOptions;
  private final ResourceBasedSlotOptions nexusSlotOptions;
  private final ScheduledExecutorService executor;

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private ResourceBasedControllerOptions controllerOptions;
    private @Nonnull ResourceBasedSlotOptions workflowSlotOptions = DEFAULT_WORKFLOW_SLOT_OPTIONS;
    private @Nonnull ResourceBasedSlotOptions activitySlotOptions = DEFAULT_ACTIVITY_SLOT_OPTIONS;
    private @Nonnull ResourceBasedSlotOptions localActivitySlotOptions =
        DEFAULT_ACTIVITY_SLOT_OPTIONS;
    private @Nonnull ResourceBasedSlotOptions nexusSlotOptions = DEFAULT_NEXUS_SLOT_OPTIONS;
    private @Nonnull ScheduledExecutorService executor;

    private Builder() {}

    public Builder setControllerOptions(ResourceBasedControllerOptions controllerOptions) {
      this.controllerOptions = controllerOptions;
      return this;
    }

    /**
     * Set the slot options for workflow tasks. Has no effect after the worker using this tuner
     * starts.
     *
     * <p>Defaults to minimum 5 slots, maximum 500 slots, and no ramp throttle.
     */
    public Builder setWorkflowSlotOptions(@Nonnull ResourceBasedSlotOptions workflowSlotOptions) {
      this.workflowSlotOptions = workflowSlotOptions;
      return this;
    }

    /**
     * Set the slot options for activity tasks. Has no effect after the worker using this tuner
     * starts.
     *
     * <p>Defaults to minimum 1 slot, maximum 1000 slots, and 50ms ramp throttle.
     */
    public Builder setActivitySlotOptions(@Nonnull ResourceBasedSlotOptions activitySlotOptions) {
      this.activitySlotOptions = activitySlotOptions;
      return this;
    }

    /**
     * Set the slot options for local activity tasks. Has no effect after the worker using this
     * tuner starts.
     *
     * <p>Defaults to minimum 1 slot, maximum 1000 slots, and 50ms ramp throttle.
     */
    public Builder setLocalActivitySlotOptions(
        @Nonnull ResourceBasedSlotOptions localActivitySlotOptions) {
      this.localActivitySlotOptions = localActivitySlotOptions;
      return this;
    }

    /**
     * Set the slot options for nexus tasks. Has no effect after the worker using this tuner starts.
     *
     * <p>Defaults to minimum 1 slot, maximum 1000 slots, and 50ms ramp throttle.
     */
    public Builder setNexusSlotOptions(@Nonnull ResourceBasedSlotOptions nexusSlotOptions) {
      this.nexusSlotOptions = nexusSlotOptions;
      return this;
    }

    /**
     * Set the executor used for checking resource usage periodically. Defaults to a two-thread
     * pool.
     */
    public Builder setExecutor(@Nonnull ScheduledExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public ResourceBasedTuner build() {
      return new ResourceBasedTuner(
          controllerOptions,
          workflowSlotOptions,
          activitySlotOptions,
          localActivitySlotOptions,
          nexusSlotOptions,
          executor);
    }
  }

  /**
   * @param controllerOptions options for the {@link ResourceBasedController} used by this tuner
   */
  public ResourceBasedTuner(
      ResourceBasedControllerOptions controllerOptions,
      ResourceBasedSlotOptions workflowSlotOptions,
      ResourceBasedSlotOptions activitySlotOptions,
      ResourceBasedSlotOptions localActivitySlotOptions,
      ResourceBasedSlotOptions nexusSlotOptions,
      ScheduledExecutorService executor) {
    this.controller = ResourceBasedController.newSystemInfoController(controllerOptions);
    this.workflowSlotOptions = workflowSlotOptions;
    this.activitySlotOptions = activitySlotOptions;
    this.localActivitySlotOptions = localActivitySlotOptions;
    this.nexusSlotOptions = nexusSlotOptions;
    this.executor = executor;
  }

  @Nonnull
  @Override
  public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
    return ResourceBasedSlotSupplier.createForWorkflow(controller, workflowSlotOptions, executor);
  }

  @Nonnull
  @Override
  public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
    return ResourceBasedSlotSupplier.createForActivity(controller, activitySlotOptions, executor);
  }

  @Nonnull
  @Override
  public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
    return ResourceBasedSlotSupplier.createForLocalActivity(
        controller, localActivitySlotOptions, executor);
  }

  @Nonnull
  @Override
  public SlotSupplier<NexusSlotInfo> getNexusSlotSupplier() {
    return ResourceBasedSlotSupplier.createForNexus(controller, nexusSlotOptions, executor);
  }
}
