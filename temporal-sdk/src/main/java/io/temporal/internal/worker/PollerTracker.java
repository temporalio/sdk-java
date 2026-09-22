package io.temporal.internal.worker;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks in-flight poll count and last successful poll time for heartbeat reporting.
 *
 * <p>A single counter feeds both the metrics system and heartbeat population, avoiding the need for
 * separate counters tracking the same value.
 *
 * <p>This object bridges the gap between poll tasks (created inside {@code start()}) and heartbeat
 * building: the intermediate worker (e.g. ActivityWorker) creates it, passes it to the poll task,
 * and later reads it when building heartbeats.
 *
 * <p>{@link #pollStarted()} and {@link #pollCompleted()} return the updated count so callers can
 * forward the value to the Tally gauge in a single operation.
 */
public class PollerTracker {
  private final AtomicInteger inFlightPolls = new AtomicInteger();
  private final AtomicReference<Instant> lastSuccessfulPollTime = new AtomicReference<>();

  /** Increments in-flight count. Returns the new value for forwarding to the Tally gauge. */
  public int pollStarted() {
    return inFlightPolls.incrementAndGet();
  }

  /** Decrements in-flight count. Returns the new value for forwarding to the Tally gauge. */
  public int pollCompleted() {
    return inFlightPolls.decrementAndGet();
  }

  /** Records the current time as the last successful poll (a poll that returned a task). */
  public void pollSucceeded() {
    lastSuccessfulPollTime.set(Instant.now());
  }

  public int getInFlightPolls() {
    return inFlightPolls.get();
  }

  public Instant getLastSuccessfulPollTime() {
    return lastSuccessfulPollTime.get();
  }
}
