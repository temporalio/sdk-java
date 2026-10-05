package io.temporal.internal.payload.storage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Accumulates external storage activity for a single workflow task. Batches may complete
 * concurrently on driver threads, so every method is synchronized.
 */
public final class StorageOperationMetrics {
  private int payloadCount;
  private long totalSizeBytes;
  private final Set<String> driverNames = new TreeSet<>();
  private final List<long[]> spans = new ArrayList<>();

  /**
   * Records a completed batch. A batch reports the start and end of its span rather than a
   * duration, leaving {@link #getTotalDuration()} to decide how overlapping spans combine.
   */
  public synchronized void recordBatch(
      int count, long sizeBytes, long startNanos, long endNanos, String driverName) {
    payloadCount += count;
    totalSizeBytes += sizeBytes;
    driverNames.add(driverName);
    spans.add(new long[] {startNanos, endNanos});
  }

  public synchronized int getPayloadCount() {
    return payloadCount;
  }

  public synchronized long getTotalSizeBytes() {
    return totalSizeBytes;
  }

  public synchronized List<String> getDriverNames() {
    return new ArrayList<>(driverNames);
  }

  /**
   * Wall-clock time storage was in flight. Batches may run concurrently, so overlapping spans are
   * counted once rather than summed.
   */
  public synchronized Duration getTotalDuration() {
    if (spans.isEmpty()) {
      return Duration.ZERO;
    }
    List<long[]> sorted = new ArrayList<>(spans);
    sorted.sort(Comparator.comparingLong(span -> span[0]));
    long total = 0;
    long currentStart = sorted.get(0)[0];
    long currentEnd = sorted.get(0)[1];
    for (int i = 1; i < sorted.size(); i++) {
      long[] span = sorted.get(i);
      if (span[0] > currentEnd) {
        total += currentEnd - currentStart;
        currentStart = span[0];
        currentEnd = span[1];
      } else if (span[1] > currentEnd) {
        currentEnd = span[1];
      }
    }
    return Duration.ofNanos(total + currentEnd - currentStart);
  }
}
