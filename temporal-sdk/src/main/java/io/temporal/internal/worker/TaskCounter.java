package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicInteger;

/** Tracks total processed and failed task counts for a worker. */
public final class TaskCounter {
  private final AtomicInteger totalProcessed = new AtomicInteger();
  private final AtomicInteger totalFailed = new AtomicInteger();

  void recordProcessed() {
    totalProcessed.incrementAndGet();
  }

  void recordFailed() {
    totalFailed.incrementAndGet();
  }

  public int getTotalProcessed() {
    return totalProcessed.get();
  }

  public int getTotalFailed() {
    return totalFailed.get();
  }
}
