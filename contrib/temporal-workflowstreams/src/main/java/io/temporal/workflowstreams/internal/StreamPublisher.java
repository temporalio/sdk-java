package io.temporal.workflowstreams.internal;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.workflowstreams.FlushTimeoutException;
import io.temporal.workflowstreams.PublishEntry;
import io.temporal.workflowstreams.PublishInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the client-side publish path: it buffers published values, batches them, and sends each
 * batch to the workflow via the injected signal function. It assigns the per-publisher dedup key (a
 * stable publisher ID plus a monotonic sequence advanced only on a confirmed send) so the workflow
 * can drop duplicates, and it retries a failed batch until the max retry duration elapses.
 *
 * <p>The signal function is injected (rather than holding a client) so the publish path can be
 * exercised in isolation. Internal to the workflow streams module.
 */
public final class StreamPublisher {

  /** Sends a publish signal to the target workflow. Throws on delivery failure. */
  @FunctionalInterface
  public interface SignalFunction {
    void send(PublishInput input);
  }

  private static final class BufItem {

    final String topic;
    final Object value;

    BufItem(String topic, Object value) {
      this.topic = topic;
      this.value = value;
    }
  }

  private final SignalFunction signal;
  private final DataConverter dataConverter;
  private final String publisherId;
  private final long batchIntervalMs;
  private final int maxBatchSize;
  private final long maxRetryDurationMs;

  private final Object stateLock = new Object();
  private List<BufItem> buffer = new ArrayList<>();
  private List<PublishEntry> pending;
  private long pendingSeq;
  private long sequence;
  private long pendingStartNanos;
  private boolean started;
  private boolean closed;
  private FlushTimeoutException deferredError;
  private ScheduledExecutorService scheduler;

  /** Serializes doFlush so concurrent callers send sequentially. */
  private final Object flushLock = new Object();

  public StreamPublisher(
      SignalFunction signal,
      DataConverter dataConverter,
      Duration batchInterval,
      int maxBatchSize,
      Duration maxRetryDuration) {
    this.signal = signal;
    this.dataConverter = dataConverter;
    this.publisherId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    this.batchIntervalMs = batchInterval.toMillis();
    this.maxBatchSize = maxBatchSize;
    this.maxRetryDurationMs = maxRetryDuration.toMillis();
  }

  /**
   * Buffers a value and lazily starts the background flush loop. Triggers an immediate flush on
   * {@code forceFlush} or once the buffer reaches the max batch size.
   */
  public void publish(String topic, Object value, boolean forceFlush) {
    boolean trigger;
    ScheduledExecutorService toTrigger = null;
    synchronized (stateLock) {
      buffer.add(new BufItem(topic, value));
      trigger = forceFlush || (maxBatchSize > 0 && buffer.size() >= maxBatchSize);
      if (!closed) {
        ensureStartedLocked();
        toTrigger = scheduler;
      }
    }
    if (trigger && toTrigger != null) {
      toTrigger.execute(this::backgroundFlush);
    }
  }

  private void ensureStartedLocked() {
    if (started || closed) {
      return;
    }
    started = true;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "temporal-workflow-stream-publisher");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::backgroundFlush, batchIntervalMs, batchIntervalMs, TimeUnit.MILLISECONDS);
  }

  private void backgroundFlush() {
    try {
      doFlush();
    } catch (FlushTimeoutException e) {
      // The pending batch was dropped and can't be recovered. Stash the error so
      // flush/close surface it and stop the loop.
      ScheduledExecutorService toStop;
      synchronized (stateLock) {
        deferredError = e;
        toStop = scheduler;
      }
      if (toStop != null) {
        toStop.shutdown();
      }
    } catch (RuntimeException e) {
      // Transient failure: pending stays set for retry on the next tick.
    }
  }

  /**
   * Sends the pending batch (retry) or encodes and sends the buffer (new batch). Serialized so
   * concurrent callers send sequentially.
   */
  private void doFlush() {
    synchronized (flushLock) {
      List<PublishEntry> batch;
      long seq;

      synchronized (stateLock) {
        if (pending != null) {
          if (System.nanoTime() - pendingStartNanos
              > TimeUnit.MILLISECONDS.toNanos(maxRetryDurationMs)) {
            // Advance the confirmed sequence so the next batch gets a fresh sequence
            // number. Without this the next batch reuses pendingSeq, which the
            // workflow may have already accepted — causing silent dedup (data loss).
            sequence = pendingSeq;
            pending = null;
            pendingSeq = 0;
            pendingStartNanos = 0;
            throw new FlushTimeoutException(
                String.format(
                    "workflowstreams: flush retry exceeded the max retry duration (%dms); pending"
                        + " batch dropped",
                    maxRetryDurationMs));
          }
          batch = pending;
          seq = pendingSeq;
        } else if (!buffer.isEmpty()) {
          // encodeBuffer may throw; the buffer is left intact for a later flush.
          batch = encodeBuffer(buffer);
          buffer = new ArrayList<>();
          seq = sequence + 1;
          pending = batch;
          pendingSeq = seq;
          pendingStartNanos = System.nanoTime();
        } else {
          return;
        }
      }

      // On failure the signal throws and pending stays set for retry.
      signal.send(new PublishInput(batch, publisherId, seq));

      synchronized (stateLock) {
        sequence = seq;
        pending = null;
        pendingSeq = 0;
        pendingStartNanos = 0;
      }
    }
  }

  private List<PublishEntry> encodeBuffer(List<BufItem> items) {
    List<PublishEntry> out = new ArrayList<>(items.size());
    for (BufItem item : items) {
      Payload payload;
      if (item.value instanceof Payload) {
        payload = (Payload) item.value;
      } else {
        payload =
            dataConverter
                .toPayload(item.value)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "workflowstreams: no payload converter accepted the published value"));
      }
      out.add(new PublishEntry(item.topic, PayloadWire.encode(payload)));
    }
    return out;
  }

  /**
   * Sends buffered (and pending) items and waits for confirmation. Returns once the items buffered
   * at call time have been signaled and acknowledged.
   *
   * @throws FlushTimeoutException if a pending batch cannot be sent within the max retry duration
   */
  public void flush() {
    throwDeferred();

    long targetSeq;
    synchronized (stateLock) {
      if (pending == null && buffer.isEmpty()) {
        return;
      }
      long baseSeq = pending != null ? pendingSeq : sequence;
      targetSeq = buffer.isEmpty() ? baseSeq : baseSeq + 1;
    }

    while (true) {
      synchronized (stateLock) {
        if (sequence >= targetSeq) {
          break;
        }
      }
      doFlush();
    }
    throwDeferred();
  }

  /**
   * Stops the background flush loop and drains any remaining items, surfacing a deferred {@link
   * FlushTimeoutException} from a prior background failure.
   */
  public void close() {
    ScheduledExecutorService toStop;
    synchronized (stateLock) {
      if (closed) {
        return;
      }
      closed = true;
      toStop = scheduler;
    }

    if (toStop != null) {
      toStop.shutdownNow();
      try {
        toStop.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Final drain: a single doFlush processes either pending OR the buffer.
    while (true) {
      synchronized (stateLock) {
        if (pending == null && buffer.isEmpty()) {
          break;
        }
      }
      doFlush();
    }
    throwDeferred();
  }

  private void throwDeferred() {
    synchronized (stateLock) {
      if (deferredError != null) {
        FlushTimeoutException e = deferredError;
        deferredError = null;
        throw e;
      }
    }
  }
}
