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

import io.temporal.common.Experimental;
import java.time.Duration;
import javax.annotation.Nonnull;

/** A {@link WorkerTuner} that attempts to allocate slots based on available system resources. */
@Experimental
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

    public ResourceBasedTuner build() {
      return new ResourceBasedTuner(
          controllerOptions,
          workflowSlotOptions,
          activitySlotOptions,
          localActivitySlotOptions,
          nexusSlotOptions);
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
      ResourceBasedSlotOptions nexusSlotOptions) {
    this.controller = ResourceBasedController.newSystemInfoController(controllerOptions);
    this.workflowSlotOptions = workflowSlotOptions;
    this.activitySlotOptions = activitySlotOptions;
    this.localActivitySlotOptions = localActivitySlotOptions;
    this.nexusSlotOptions = nexusSlotOptions;
  }

  @Nonnull
  @Override
  public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
    return ResourceBasedSlotSupplier.createForWorkflow(controller, workflowSlotOptions);
  }

  @Nonnull
  @Override
  public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
    return ResourceBasedSlotSupplier.createForActivity(controller, activitySlotOptions);
  }

  @Nonnull
  @Override
  public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
    return ResourceBasedSlotSupplier.createForLocalActivity(controller, localActivitySlotOptions);
  }

  @Nonnull
  @Override
  public SlotSupplier<NexusSlotInfo> getNexusSlotSupplier() {
    return ResourceBasedSlotSupplier.createForNexus(controller, nexusSlotOptions);
  }
}
