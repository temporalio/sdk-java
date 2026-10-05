package io.temporal.internal.payload.storage;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class StorageOperationMetricsTest {

  private static long nanos(long millis) {
    return TimeUnit.MILLISECONDS.toNanos(millis);
  }

  @Test
  public void noBatchesReportsZero() {
    StorageOperationMetrics metrics = new StorageOperationMetrics();
    assertEquals(Duration.ZERO, metrics.getTotalDuration());
    assertEquals(0, metrics.getPayloadCount());
    assertEquals(0, metrics.getTotalSizeBytes());
  }

  @Test
  public void aggregatesCountSizeAndDriverNames() {
    StorageOperationMetrics metrics = new StorageOperationMetrics();
    metrics.recordBatch(2, 1024, nanos(0), nanos(100), "s3");
    metrics.recordBatch(3, 2048, nanos(0), nanos(100), "gcs");
    metrics.recordBatch(1, 512, nanos(0), nanos(100), "s3");
    assertEquals(6, metrics.getPayloadCount());
    assertEquals(3584, metrics.getTotalSizeBytes());
    assertEquals(Arrays.asList("gcs", "s3"), metrics.getDriverNames());
  }

  @Test
  public void concurrentBatchesCountedOnce() {
    StorageOperationMetrics metrics = new StorageOperationMetrics();
    // Summing each batch would report 200ms; the real wall-clock span is 150ms.
    metrics.recordBatch(1, 1, nanos(0), nanos(100), "s3");
    metrics.recordBatch(1, 1, nanos(50), nanos(150), "gcs");
    assertEquals(Duration.ofMillis(150), metrics.getTotalDuration());
  }

  @Test
  public void disjointBatchesSummed() {
    StorageOperationMetrics metrics = new StorageOperationMetrics();
    metrics.recordBatch(1, 1, nanos(0), nanos(100), "s3");
    metrics.recordBatch(1, 1, nanos(200), nanos(300), "s3");
    assertEquals(Duration.ofMillis(200), metrics.getTotalDuration());
  }

  @Test
  public void adjacentAndNestedBatchesMerged() {
    StorageOperationMetrics metrics = new StorageOperationMetrics();
    metrics.recordBatch(1, 1, nanos(0), nanos(100), "s3");
    metrics.recordBatch(1, 1, nanos(100), nanos(200), "s3");
    metrics.recordBatch(1, 1, nanos(120), nanos(180), "s3");
    assertEquals(Duration.ofMillis(200), metrics.getTotalDuration());
  }
}
