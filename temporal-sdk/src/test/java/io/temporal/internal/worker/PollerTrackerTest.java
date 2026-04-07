package io.temporal.internal.worker;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;

public class PollerTrackerTest {

  @Test
  public void testPollStartedIncrementsAndReturnsCount() {
    PollerTracker tracker = new PollerTracker();
    assertEquals(0, tracker.getInFlightPolls());
    assertEquals(1, tracker.pollStarted());
    assertEquals(1, tracker.getInFlightPolls());
    assertEquals(2, tracker.pollStarted());
    assertEquals(2, tracker.getInFlightPolls());
  }

  @Test
  public void testPollCompletedDecrementsAndReturnsCount() {
    PollerTracker tracker = new PollerTracker();
    tracker.pollStarted();
    tracker.pollStarted();
    assertEquals(1, tracker.pollCompleted());
    assertEquals(1, tracker.getInFlightPolls());
    assertEquals(0, tracker.pollCompleted());
    assertEquals(0, tracker.getInFlightPolls());
  }

  @Test
  public void testPollSucceededSetsLastPollTime() {
    PollerTracker tracker = new PollerTracker();
    tracker.pollSucceeded();
    assertNotNull(tracker.getLastSuccessfulPollTime());
  }

  @Test
  public void testLastPollTimeInitiallyNull() {
    PollerTracker tracker = new PollerTracker();
    assertNull(tracker.getLastSuccessfulPollTime());
  }

  @Test
  public void testConcurrentPollTracking() throws Exception {
    PollerTracker tracker = new PollerTracker();
    int threadCount = 8;
    int opsPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                for (int j = 0; j < opsPerThread; j++) {
                  tracker.pollStarted();
                  tracker.pollCompleted();
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(5, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(0, tracker.getInFlightPolls());
  }

  @Test
  public void testMultipleSuccessfulPolls() {
    PollerTracker tracker = new PollerTracker();
    tracker.pollSucceeded();
    Instant first = tracker.getLastSuccessfulPollTime();
    assertNotNull(first);

    tracker.pollSucceeded();
    Instant second = tracker.getLastSuccessfulPollTime();
    assertNotNull(second);
    assertFalse("Last poll time should advance with each successful poll", second.isBefore(first));
  }
}
