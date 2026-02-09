package io.temporal.worker.tuning;

import static org.junit.Assert.*;

import com.uber.m3.tally.NoopScope;
import io.temporal.internal.worker.SlotReservationData;
import io.temporal.internal.worker.TrackingSlotSupplier;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Test to demonstrate the recursion issue in ResourceBasedSlotSupplier.scheduleSlotAcquisition()
 * where a long chain of futures is created when resources remain unavailable.
 */
public class ResourceBasedSlotSupplierNonRecursiveTest {

  static class TestResourceController extends ResourceBasedController {
    private final AtomicBoolean allow;
    private final AtomicInteger pidDecisionCallCount;

    public TestResourceController(AtomicBoolean allow) {
      super(
          ResourceBasedControllerOptions.newBuilder(0.5, 0.5)
              .setMemoryPGain(1)
              .setMemoryIGain(0)
              .setMemoryDGain(0)
              .setMemoryOutputThreshold(0)
              .setCpuPGain(1)
              .setCpuIGain(0)
              .setCpuDGain(0)
              .setCpuOutputThreshold(0)
              .build(),
          new JVMSystemResourceInfo());
      this.allow = allow;
      this.pidDecisionCallCount = new AtomicInteger(0);
    }

    @Override
    boolean pidDecision() {
      pidDecisionCallCount.incrementAndGet();
      return allow.get();
    }

    public int getPidDecisionCallCount() {
      return pidDecisionCallCount.get();
    }
  }

  /**
   * This test demonstrates the issue where reserveSlot creates a long chain of CompletableFutures
   * through recursive calls to scheduleSlotAcquisition. When resources remain unavailable for an
   * extended period, the future chain grows indefinitely and may never resolve.
   *
   * <p>The recursion happens in scheduleSlotAcquisition at line 219: when tryReserveSlot fails, it
   * schedules another attempt after 10ms by calling scheduleSlotAcquisition again. Each recursive
   * call creates a new CompletableFuture that depends on the previous one, building a long chain.
   */
  @Test(timeout = 10000)
  public void testLongRunningResourceStarvationCreatesDeepFutureChain() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0) // Set to 0 so we go through scheduleSlotAcquisition
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO) // No throttle delay to speed up recursion
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve the first slot to ensure we're above minimum (it should complete immediately since
    // we're at 0 slots)
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    SlotPermit firstPermit = firstFuture.get(1, TimeUnit.SECONDS);
    assertNotNull(firstPermit);

    // Now try to reserve a second slot - this should go through scheduleSlotAcquisition
    // and will recursively retry since controller always denies
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Wait a bit to allow multiple recursive attempts to stack up
    // With 10ms delay between attempts, in 500ms we'd expect ~50 recursive calls
    Thread.sleep(500);

    // The future should not have completed yet since resources are never available
    assertFalse("Future should not be done when resources are unavailable", secondFuture.isDone());

    // Check that many recursive attempts have been made
    int callCount = controller.getPidDecisionCallCount();
    assertTrue(
        "Expected many pidDecision calls due to recursion, but got " + callCount, callCount > 20);

    // Now allow resources and verify the future eventually completes
    allow.set(true);
    SlotPermit secondPermit = secondFuture.get(2, TimeUnit.SECONDS);
    assertNotNull("Future should complete once resources are available", secondPermit);

    scheduler.shutdownNow();
  }

  /**
   * This test demonstrates that when resources are unavailable for an extended period, the future
   * from reserveSlot might not complete within a reasonable time, even when later allowed.
   *
   * <p>This is particularly problematic because each recursive call adds another layer to the
   * CompletableFuture chain, potentially causing: - Stack overflow with very long chains - Memory
   * buildup from the chain of futures - Delayed completion even after resources become available
   */
  @Test
  public void testFutureDoesNotCompleteWithProlongedResourceStarvation() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Try to reserve second slot - will enter recursion loop
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Let it build a very deep recursion chain (2 seconds = ~200 recursive calls at 10ms each)
    Thread.sleep(2000);

    // Try to get the result with a short timeout - should fail
    try {
      secondFuture.get(100, TimeUnit.MILLISECONDS);
      fail("Future should not complete when resources are unavailable");
    } catch (TimeoutException e) {
      // Expected - the future is still waiting
    }

    // Even if we allow resources now, the deep future chain might take a while to unwind
    allow.set(true);

    // This demonstrates the problem: even though resources are now available,
    // the future might not resolve quickly due to the deep chain
    long startTime = System.currentTimeMillis();
    SlotPermit permit = secondFuture.get(5, TimeUnit.SECONDS);
    long duration = System.currentTimeMillis() - startTime;

    assertNotNull(permit);

    // Log how long it took to complete after allowing resources
    System.out.println(
        "Took "
            + duration
            + "ms to complete after allowing resources (with "
            + controller.getPidDecisionCallCount()
            + " recursive attempts)");

    scheduler.shutdownNow();
  }

  /**
   * Test demonstrating that cancellation of the future doesn't immediately stop the recursive chain
   * of scheduled tasks.
   */
  @Test
  public void testFutureCancellationWithRecursiveChain() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Try to reserve second slot
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Let recursion build up
    Thread.sleep(500);

    int callCountBeforeCancel = controller.getPidDecisionCallCount();

    // Abort the reservation
    SlotPermit abortResult = secondFuture.abortReservation();
    assertNull("Abort should return null since future was aborted", abortResult);
    assertTrue("Future should be cancelled", secondFuture.isCancelled());

    // Wait a bit more and check if pidDecision is still being called
    // If cancellation properly propagates, these calls should stop
    Thread.sleep(200);

    int callCountAfterCancel = controller.getPidDecisionCallCount();

    // This test will likely show that calls continue even after cancellation
    // due to the recursive chain already in flight
    System.out.println(
        "PID decisions before cancel: "
            + callCountBeforeCancel
            + ", after cancel: "
            + callCountAfterCancel
            + ", new calls: "
            + (callCountAfterCancel - callCountBeforeCancel));

    scheduler.shutdownNow();
  }

  /**
   * This test demonstrates the most severe case: when resources NEVER become available, the future
   * will NEVER resolve - it will keep scheduling recursive attempts indefinitely.
   *
   * <p>This is the core bug reported by users: reserveSlot returns a future that never gets
   * resolved because the recursive chain continues forever.
   */
  @Test(timeout = 15000)
  public void testFutureNeverResolvesWhenResourcesNeverAvailable() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Try to reserve second slot - this will never complete
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Wait for 5 seconds (should create ~500 recursive calls)
    Thread.sleep(5000);

    // The future should still not be done
    assertFalse(
        "Future should never complete when resources are never available", secondFuture.isDone());

    // Verify that recursive attempts are still happening
    int callCountBefore = controller.getPidDecisionCallCount();
    Thread.sleep(500);
    int callCountAfter = controller.getPidDecisionCallCount();

    assertTrue(
        "Recursive attempts should continue indefinitely (before: "
            + callCountBefore
            + ", after: "
            + callCountAfter
            + ")",
        callCountAfter > callCountBefore);

    System.out.println(
        "After 5.5 seconds of resource starvation: "
            + callCountAfter
            + " recursive attempts and counting...");
    System.out.println("Future is still waiting and will NEVER complete without intervention");

    // Clean up - abort the reservation to stop the recursion
    SlotPermit cleanupResult = secondFuture.abortReservation();
    assertNull("Cleanup abort should return null", cleanupResult);

    scheduler.shutdownNow();
  }

  /**
   * This test demonstrates the severe bug: even when resources become available, the future may
   * never resolve due to the extremely deep recursive chain. This is the actual reported bug.
   */
  @Test(timeout = 90000)
  public void testFutureNeverResolvesEvenAfterResourcesBecomeAvailable() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Try to reserve second slot - will build up recursive chain
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Build up a very deep chain - 30 seconds should create ~3000 recursive calls
    System.out.println("Building deep recursive chain for 30 seconds...");
    Thread.sleep(30000);

    int recursiveAttempts = controller.getPidDecisionCallCount();
    System.out.println("Built chain with " + recursiveAttempts + " recursive attempts");

    // Now ALLOW resources - this is the critical test
    System.out.println("Allowing resources NOW...");
    allow.set(true);

    // Try to get the result - this should complete quickly, but might NEVER complete
    // if the chain is too deep
    try {
      System.out.println("Waiting for future to complete (30 second timeout)...");
      long startTime = System.currentTimeMillis();
      SlotPermit permit = secondFuture.get(30, TimeUnit.SECONDS);
      long duration = System.currentTimeMillis() - startTime;

      if (permit != null) {
        System.out.println(
            "SUCCESS: Future completed in " + duration + "ms after allowing resources");
      } else {
        fail("Future completed but returned null permit");
      }
    } catch (TimeoutException e) {
      // This is the BUG - even though resources are now available, the future never completes
      System.out.println(
          "BUG REPRODUCED: Future did NOT complete even after 30 seconds of resources being"
              + " available!");
      System.out.println(
          "The recursive chain of "
              + recursiveAttempts
              + " futures is too deep and never resolves");
      fail(
          "Future never completed even after resources became available - this is the reported"
              + " bug!");
    } finally {
      SlotPermit ignored = secondFuture.abortReservation();
      scheduler.shutdownNow();
    }
  }

  /**
   * This test measures the memory and performance impact of the deep future chain. With very long
   * starvation periods, we can see if there are practical limits.
   */
  @Test
  public void testMemoryAndPerformanceImpactOfDeepRecursion() throws Exception {
    AtomicBoolean allow = new AtomicBoolean(false);
    TestResourceController controller = new TestResourceController(allow);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    ResourceBasedSlotSupplier<ActivitySlotInfo> supplier =
        ResourceBasedSlotSupplier.createForActivity(
            controller,
            ResourceBasedSlotOptions.newBuilder()
                .setMinimumSlots(0)
                .setMaximumSlots(10)
                .setRampThrottle(Duration.ZERO)
                .build(),
            scheduler);

    TrackingSlotSupplier<ActivitySlotInfo> tracking =
        new TrackingSlotSupplier<>(supplier, new NoopScope());

    // Reserve first slot
    SlotSupplierFuture firstFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id1", "bid1"));
    firstFuture.get(1, TimeUnit.SECONDS);

    // Record memory before
    Runtime runtime = Runtime.getRuntime();
    runtime.gc();
    long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

    // Try to reserve second slot
    SlotSupplierFuture secondFuture =
        tracking.reserveSlot(new SlotReservationData("tq", "id2", "bid2"));

    // Build up a very long chain (10 seconds = ~1000 recursive calls)
    Thread.sleep(10000);

    // Record memory after
    long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
    long memoryIncrease = memoryAfter - memoryBefore;

    int recursiveAttempts = controller.getPidDecisionCallCount();

    System.out.println("=== Memory and Performance Impact ===");
    System.out.println("Recursive attempts: " + recursiveAttempts);
    System.out.println("Memory increase: " + (memoryIncrease / 1024) + " KB");
    System.out.println(
        "Approximate memory per recursive call: "
            + (memoryIncrease / recursiveAttempts)
            + " bytes");

    // Now allow resources and measure how long it takes to unwind
    allow.set(true);
    long startTime = System.currentTimeMillis();
    SlotPermit permit = secondFuture.get(10, TimeUnit.SECONDS);
    long unwinding = System.currentTimeMillis() - startTime;

    assertNotNull(permit);

    System.out.println("Time to unwind " + recursiveAttempts + " futures: " + unwinding + "ms");

    // If unwinding takes more than a few hundred milliseconds with 1000 calls,
    // there could be practical issues with very deep chains
    if (unwinding > 500) {
      System.out.println(
          "WARNING: Unwinding took significant time - very deep chains could cause issues");
    }

    scheduler.shutdownNow();
  }
}
