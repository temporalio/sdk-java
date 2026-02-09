package io.temporal.worker.tuning;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Implements a {@link SlotSupplier} based on resource usage for a particular slot type. */
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
  public SlotSupplierFuture reserveSlot(SlotReserveContext<SI> ctx) throws Exception {
    if (ctx.getNumIssuedSlots() < options.getMinimumSlots()) {
      return SlotSupplierFuture.completedFuture(new SlotPermit());
    }
    return tryReserveSlot(ctx)
        .map(SlotSupplierFuture::completedFuture)
        .orElseGet(() -> scheduleSlotAcquisition(ctx));
  }

  private SlotSupplierFuture scheduleSlotAcquisition(SlotReserveContext<SI> ctx) {
    CompletableFuture<SlotPermit> resultFuture = new CompletableFuture<>();
    AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

    Runnable pollingTask =
        new Runnable() {
          @Override
          public void run() {
            if (resultFuture.isDone()) {
              return; // Already completed or cancelled
            }

            try {
              Optional<SlotPermit> permit = tryReserveSlot(ctx);
              if (permit.isPresent()) {
                resultFuture.complete(permit.get());
              } else {
                // Calculate delay respecting ramp throttle on each retry
                Duration mustWaitFor;
                try {
                  mustWaitFor = options.getRampThrottle().minus(timeSinceLastSlotIssued());
                } catch (ArithmeticException e) {
                  mustWaitFor = Duration.ZERO;
                }

                // Use at least 10ms to avoid tight spinning, but respect ramp throttle if longer
                long delayMs = Math.max(10, mustWaitFor.toMillis());
                taskRef.set(scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS));
              }
            } catch (Exception e) {
              resultFuture.completeExceptionally(e);
            }
          }
        };

    // Calculate initial delay based on ramp throttle
    Duration mustWaitFor;
    try {
      mustWaitFor = options.getRampThrottle().minus(timeSinceLastSlotIssued());
    } catch (ArithmeticException e) {
      mustWaitFor = Duration.ZERO;
    }

    long initialDelayMs = Math.max(0, mustWaitFor.toMillis());

    // Schedule the initial attempt
    taskRef.set(scheduler.schedule(pollingTask, initialDelayMs, TimeUnit.MILLISECONDS));

    return SlotSupplierFuture.fromCompletableFuture(
        resultFuture,
        () -> {
          // Cancel the scheduled task when aborting
          ScheduledFuture<?> task = taskRef.get();
          if (task != null) {
            task.cancel(true);
          }
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
