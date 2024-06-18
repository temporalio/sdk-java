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
      new ResourceBasedSlotOptions(2, 500, Duration.ZERO);
  public static final ResourceBasedSlotOptions DEFAULT_ACTIVITY_SLOT_OPTIONS =
      new ResourceBasedSlotOptions(1, 1000, Duration.ofMillis(50));

  private final ResourceBasedController controller;
  private ResourceBasedSlotOptions workflowSlotOptions;
  private ResourceBasedSlotOptions activitySlotOptions;
  private ResourceBasedSlotOptions localActivitySlotOptions;

  /**
   * @param controllerOptions options for the {@link ResourceBasedController} used by this tuner
   */
  public ResourceBasedTuner(ResourceBasedControllerOptions controllerOptions) {
    this.controller = ResourceBasedController.newSystemInfoController(controllerOptions);
  }

  /**
   * Set the slot options for workflow tasks. Has no effect after the worker using this tuner
   * starts.
   *
   * <p>Defaults to minimum 2 slots, maximum 500 slots, and no ramp throttle.
   */
  public void setWorkflowSlotOptions(ResourceBasedSlotOptions workflowSlotOptions) {
    this.workflowSlotOptions = workflowSlotOptions;
  }

  /**
   * Set the slot options for activity tasks. Has no effect after the worker using this tuner
   * starts.
   *
   * <p>Defaults to minimum 1 slot, maximum 1000 slots, and 50ms ramp throttle.
   */
  public void setActivitySlotOptions(ResourceBasedSlotOptions activitySlotOptions) {
    this.activitySlotOptions = activitySlotOptions;
  }

  /**
   * Set the slot options for local activity tasks. Has no effect after the worker using this tuner
   * starts.
   *
   * <p>Defaults to minimum 1 slot, maximum 1000 slots, and 50ms ramp throttle.
   */
  public void setLocalActivitySlotOptions(ResourceBasedSlotOptions localActivitySlotOptions) {
    this.localActivitySlotOptions = localActivitySlotOptions;
  }

  @Nonnull
  @Override
  public SlotSupplier<WorkflowSlotInfo> getWorkflowTaskSlotSupplier() {
    return new ResourceBasedSlotSupplier<>(
        controller,
        workflowSlotOptions == null ? DEFAULT_WORKFLOW_SLOT_OPTIONS : workflowSlotOptions);
  }

  @Nonnull
  @Override
  public SlotSupplier<ActivitySlotInfo> getActivityTaskSlotSupplier() {
    return new ResourceBasedSlotSupplier<>(
        controller,
        activitySlotOptions == null ? DEFAULT_ACTIVITY_SLOT_OPTIONS : activitySlotOptions);
  }

  @Nonnull
  @Override
  public SlotSupplier<LocalActivitySlotInfo> getLocalActivitySlotSupplier() {
    return new ResourceBasedSlotSupplier<>(
        controller,
        localActivitySlotOptions == null
            ? DEFAULT_ACTIVITY_SLOT_OPTIONS
            : localActivitySlotOptions);
  }
}
