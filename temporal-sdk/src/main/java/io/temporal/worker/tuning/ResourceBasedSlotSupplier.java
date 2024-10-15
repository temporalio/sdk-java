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
import java.time.Instant;
import java.util.Optional;

/** Implements a {@link SlotSupplier} based on resource usage for a particular slot type. */
@Experimental
public class ResourceBasedSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {

  private final ResourceBasedController resourceController;
  private final ResourceBasedSlotOptions options;
  private Instant lastSlotIssuedAt = Instant.EPOCH;

  /**
   * Construct a slot supplier for workflow tasks with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<WorkflowSlotInfo> createForWorkflow(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(
        WorkflowSlotInfo.class, resourceBasedController, options);
  }

  /**
   * Construct a slot supplier for activity tasks with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<ActivitySlotInfo> createForActivity(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(
        ActivitySlotInfo.class, resourceBasedController, options);
  }

  /**
   * Construct a slot supplier for local activities with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<LocalActivitySlotInfo> createForLocalActivity(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(
        LocalActivitySlotInfo.class, resourceBasedController, options);
  }

  /**
   * Construct a slot supplier for nexus tasks with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<NexusSlotInfo> createForNexus(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(NexusSlotInfo.class, resourceBasedController, options);
  }

  private ResourceBasedSlotSupplier(
      Class<SI> clazz,
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options) {
    this.resourceController = resourceBasedController;
    // Merge default options for any unset fields
    if (WorkflowSlotInfo.class.isAssignableFrom(clazz)) {
      this.options =
          ResourceBasedSlotOptions.newBuilder()
              .setMinimumSlots(
                  options.getMinimumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_WORKFLOW_SLOT_OPTIONS.getMinimumSlots()
                      : options.getMinimumSlots())
              .setMaximumSlots(
                  options.getMaximumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_WORKFLOW_SLOT_OPTIONS.getMaximumSlots()
                      : options.getMaximumSlots())
              .setRampThrottle(
                  options.getRampThrottle() == null
                      ? ResourceBasedTuner.DEFAULT_WORKFLOW_SLOT_OPTIONS.getRampThrottle()
                      : options.getRampThrottle())
              .build();
    } else if (ActivitySlotInfo.class.isAssignableFrom(clazz)
        || LocalActivitySlotInfo.class.isAssignableFrom(clazz)) {
      this.options =
          ResourceBasedSlotOptions.newBuilder()
              .setMinimumSlots(
                  options.getMinimumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS.getMinimumSlots()
                      : options.getMinimumSlots())
              .setMaximumSlots(
                  options.getMaximumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS.getMaximumSlots()
                      : options.getMaximumSlots())
              .setRampThrottle(
                  options.getRampThrottle() == null
                      ? ResourceBasedTuner.DEFAULT_ACTIVITY_SLOT_OPTIONS.getRampThrottle()
                      : options.getRampThrottle())
              .build();
    } else {
      this.options =
          ResourceBasedSlotOptions.newBuilder()
              .setMinimumSlots(
                  options.getMinimumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_NEXUS_SLOT_OPTIONS.getMinimumSlots()
                      : options.getMinimumSlots())
              .setMaximumSlots(
                  options.getMaximumSlots() == 0
                      ? ResourceBasedTuner.DEFAULT_NEXUS_SLOT_OPTIONS.getMaximumSlots()
                      : options.getMaximumSlots())
              .setRampThrottle(
                  options.getRampThrottle() == null
                      ? ResourceBasedTuner.DEFAULT_NEXUS_SLOT_OPTIONS.getRampThrottle()
                      : options.getRampThrottle())
              .build();
    }
  }

  @Override
  public SlotPermit reserveSlot(SlotReserveContext<SI> ctx) throws InterruptedException {
    while (true) {
      if (ctx.getNumIssuedSlots() < options.getMinimumSlots()) {
        return new SlotPermit();
      } else {
        Duration mustWaitFor;
        try {
          mustWaitFor = options.getRampThrottle().minus(timeSinceLastSlotIssued());
        } catch (ArithmeticException e) {
          mustWaitFor = Duration.ZERO;
        }
        if (mustWaitFor.compareTo(Duration.ZERO) > 0) {
          Thread.sleep(mustWaitFor.toMillis());
        }

        Optional<SlotPermit> permit = tryReserveSlot(ctx);
        if (permit.isPresent()) {
          return permit.get();
        } else {
          Thread.sleep(10);
        }
      }
    }
  }

  @Override
  public Optional<SlotPermit> tryReserveSlot(SlotReserveContext<SI> ctx) {
    int numIssued = ctx.getNumIssuedSlots();
    if (numIssued < options.getMinimumSlots()
        || (timeSinceLastSlotIssued().compareTo(options.getRampThrottle()) > 0
            && numIssued < options.getMaximumSlots()
            && resourceController.pidDecision())) {
      lastSlotIssuedAt = Instant.now();
      return Optional.of(new SlotPermit());
    }
    return Optional.empty();
  }

  @Override
  public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {}

  @Override
  public void releaseSlot(SlotReleaseContext<SI> ctx) {}

  public ResourceBasedController getResourceController() {
    return resourceController;
  }

  private Duration timeSinceLastSlotIssued() {
    return Duration.between(lastSlotIssuedAt, Instant.now());
  }
}
