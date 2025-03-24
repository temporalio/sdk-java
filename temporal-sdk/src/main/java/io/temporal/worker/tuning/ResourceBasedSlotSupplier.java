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
import java.util.concurrent.*;

/** Implements a {@link SlotSupplier} based on resource usage for a particular slot type. */
@Experimental
public class ResourceBasedSlotSupplier<SI extends SlotInfo> implements SlotSupplier<SI> {

  private final ResourceBasedController resourceController;
  private final ResourceBasedSlotOptions options;
  private Instant lastSlotIssuedAt = Instant.EPOCH;
  // For slot reservations that are waiting to re-check resource usage
  private final ScheduledExecutorService scheduler;
  private static ScheduledExecutorService defaultScheduler;

  /**
   * Construct a slot supplier for workflow tasks with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<WorkflowSlotInfo> createForWorkflow(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(
        WorkflowSlotInfo.class, resourceBasedController, options, null);
  }

  /**
   * As {@link #createForWorkflow(ResourceBasedController, ResourceBasedSlotOptions)}, but allows
   * overriding the internal thread pool. It is recommended to share the same executor across all
   * resource based slot suppliers in a worker.
   */
  public static ResourceBasedSlotSupplier<WorkflowSlotInfo> createForWorkflow(
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options,
      ScheduledExecutorService scheduler) {
    return new ResourceBasedSlotSupplier<>(
        WorkflowSlotInfo.class, resourceBasedController, options, scheduler);
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
        ActivitySlotInfo.class, resourceBasedController, options, null);
  }

  /**
   * As {@link #createForActivity(ResourceBasedController, ResourceBasedSlotOptions)}, but allows
   * overriding the internal thread pool. It is recommended to share the same executor across all
   * resource based slot suppliers in a worker.
   */
  public static ResourceBasedSlotSupplier<ActivitySlotInfo> createForActivity(
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options,
      ScheduledExecutorService scheduler) {
    return new ResourceBasedSlotSupplier<>(
        ActivitySlotInfo.class, resourceBasedController, options, scheduler);
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
        LocalActivitySlotInfo.class, resourceBasedController, options, null);
  }

  /**
   * As {@link #createForLocalActivity(ResourceBasedController, ResourceBasedSlotOptions)}, but
   * allows overriding the internal thread pool. It is recommended to share the same executor across
   * all resource based slot suppliers in a worker.
   */
  public static ResourceBasedSlotSupplier<LocalActivitySlotInfo> createForLocalActivity(
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options,
      ScheduledExecutorService scheduler) {
    return new ResourceBasedSlotSupplier<>(
        LocalActivitySlotInfo.class, resourceBasedController, options, scheduler);
  }

  /**
   * Construct a slot supplier for nexus tasks with the given resource controller and options.
   *
   * <p>The resource controller must be the same among all slot suppliers in a worker. If you want
   * to use resource-based tuning for all slot suppliers, prefer {@link ResourceBasedTuner}.
   */
  public static ResourceBasedSlotSupplier<NexusSlotInfo> createForNexus(
      ResourceBasedController resourceBasedController, ResourceBasedSlotOptions options) {
    return new ResourceBasedSlotSupplier<>(
        NexusSlotInfo.class, resourceBasedController, options, null);
  }

  /**
   * As {@link #createForNexus(ResourceBasedController, ResourceBasedSlotOptions)}, but allows
   * overriding the internal thread pool. It is recommended to share the same executor across all
   * resource based slot suppliers in a worker.
   */
  public static ResourceBasedSlotSupplier<NexusSlotInfo> createForNexus(
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options,
      ScheduledExecutorService scheduler) {
    return new ResourceBasedSlotSupplier<>(
        NexusSlotInfo.class, resourceBasedController, options, scheduler);
  }

  private ResourceBasedSlotSupplier(
      Class<SI> clazz,
      ResourceBasedController resourceBasedController,
      ResourceBasedSlotOptions options,
      ScheduledExecutorService scheduler) {
    this.resourceController = resourceBasedController;
    if (scheduler == null) {
      this.scheduler = getDefaultScheduler();
    } else {
      this.scheduler = scheduler;
    }
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
  public CompletableFuture<SlotPermit> reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    if (ctx.getNumIssuedSlots() < options.getMinimumSlots()) {
      return CompletableFuture.completedFuture(new SlotPermit());
    }
    return tryReserveSlot(ctx)
        .map(CompletableFuture::completedFuture)
        .orElseGet(() -> scheduleSlotAcquisition(ctx));
  }

  private CompletableFuture<SlotPermit> scheduleSlotAcquisition(SlotReserveContext<SI> ctx) {
    Duration mustWaitFor;
    try {
      mustWaitFor = options.getRampThrottle().minus(timeSinceLastSlotIssued());
    } catch (ArithmeticException e) {
      mustWaitFor = Duration.ZERO;
    }

    CompletableFuture<Void> permitFuture;
    if (mustWaitFor.compareTo(Duration.ZERO) > 0) {
      permitFuture =
          CompletableFuture.supplyAsync(() -> null, delayedExecutor(mustWaitFor.toMillis()));
    } else {
      permitFuture = CompletableFuture.completedFuture(null);
    }

    // After the delay, try to reserve the slot
    return permitFuture.thenCompose(
        ignored -> {
          Optional<SlotPermit> permit = tryReserveSlot(ctx);
          // If we couldn't get a slot this time, delay for a short period and try again
          return permit
              .map(CompletableFuture::completedFuture)
              .orElseGet(
                  () ->
                      CompletableFuture.supplyAsync(() -> null, delayedExecutor(10))
                          .thenCompose(ig -> scheduleSlotAcquisition(ctx)));
        });
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

  // Polyfill for Java 9 delayedExecutor
  private Executor delayedExecutor(long delay) {
    return r -> scheduler.schedule(() -> scheduler.execute(r), delay, TimeUnit.MILLISECONDS);
  }

  private static ScheduledExecutorService getDefaultScheduler() {
    synchronized (ResourceBasedSlotSupplier.class) {
      if (defaultScheduler == null) {
        defaultScheduler =
            Executors.newScheduledThreadPool(
                // Two threads seem needed here, so that reading PID decisions doesn't interfere
                // overly with firing off scheduled tasks or one another.
                2,
                r -> {
                  Thread t = new Thread(r);
                  t.setName("ResourceBasedSlotSupplier.scheduler");
                  t.setDaemon(true);
                  return t;
                });
      }
      return defaultScheduler;
    }
  }
}
