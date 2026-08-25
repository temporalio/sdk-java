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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Owns the client-side publish path: it buffers published values, batches them, and sends each
 * batch to the workflow via the injected signal function. It assigns the per-publisher dedup key (a
 * stable publisher ID plus a monotonic sequence advanced only on a confirmed send) so the workflow
 * can drop duplicates, and it retries a failed batch until the max retry duration elapses. Once a
 * background flush exceeds that duration the background loop stops for good and the resulting
 * {@link FlushTimeoutException} is deferred to the next {@link #flush} or {@link #close}.
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

  private final SignalFunction signal;
  private final DataConverter dataConverter;
  private final String publisherId;
  private final long batchIntervalMs;
  private final int maxBatchSize;
  private final long maxRetryDurationMs;
  // When null, the publisher creates a single-thread executor it owns and shuts down in close().
  @Nullable private final ScheduledExecutorService userExecutor;

  private final Object stateLock = new Object();
  private List<PublishEntry> buffer = new ArrayList<>();
  private List<PublishEntry> pending;
  private long pendingSeq;
  private long sequence;
  private long pendingStartNanos;
  private boolean started;
  private boolean closed;
  // Set when a background flush timed out: the loop is stopped for good, so no background send may
  // run before flush()/close() surfaces the deferred error. Guarded by stateLock.
  private boolean loopStopped;
  private FlushTimeoutException deferredError;
  // The executor driving the flush loop once started; the owned one when no user executor was
  // supplied. Guarded by stateLock.
  private ScheduledExecutorService scheduler;
  // The periodic flush tick, tracked so it can be cancelled without shutting down a user-supplied
  // executor. Guarded by stateLock.
  private ScheduledFuture<?> flushTask;

  /** Serializes doFlush so concurrent callers send sequentially. */
  private final Object flushLock = new Object();

  public StreamPublisher(
      SignalFunction signal,
      DataConverter dataConverter,
      Duration batchInterval,
      int maxBatchSize,
      Duration maxRetryDuration) {
    this(signal, dataConverter, batchInterval, maxBatchSize, maxRetryDuration, null);
  }

  /**
   * @param executor drives the background flush loop (the periodic ticks and the flushes triggered
   *     by a full buffer or {@code forceFlush}). When non-null the caller owns its lifecycle and it
   *     is never shut down by this publisher, so many publishers can share one executor; when null
   *     a single-thread executor is created lazily, owned by this publisher, and shut down by
   *     {@link #close}. Flushes block while signaling the workflow, so each in-flight flush
   *     occupies an executor thread for the duration of the send.
   */
  public StreamPublisher(
      SignalFunction signal,
      DataConverter dataConverter,
      Duration batchInterval,
      int maxBatchSize,
      Duration maxRetryDuration,
      @Nullable ScheduledExecutorService executor) {
    this.signal = signal;
    this.dataConverter = dataConverter;
    this.publisherId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    this.batchIntervalMs = batchInterval.toMillis();
    this.maxBatchSize = maxBatchSize;
    this.maxRetryDurationMs = maxRetryDuration.toMillis();
    this.userExecutor = executor;
  }

  /**
   * Converts and buffers a value, lazily starting the background flush loop. Triggers an immediate
   * flush on {@code forceFlush} or once the buffer reaches the max batch size.
   *
   * <p>Conversion happens here, on the caller's thread, so an unconvertible value fails the {@code
   * publish} call itself instead of poisoning the buffer and silently wedging every later item
   * behind it in the background flush loop.
   *
   * <p>After a background flush exceeds the max retry duration the background loop is stopped
   * permanently — neither the periodic tick nor a {@code forceFlush}/max-batch-size trigger sends
   * again. Items published afterwards stay buffered until {@link #flush} or {@link #close} drains
   * them, and that call surfaces the deferred {@link FlushTimeoutException} first (flush) or after
   * the final drain (close). This keeps a caller-owned executor untouched without letting more data
   * ship before the failure is reported.
   *
   * @throws RuntimeException if no configured payload converter accepts {@code value}
   */
  public void publish(String topic, Object value, boolean forceFlush) {
    PublishEntry entry = encode(topic, value);
    boolean trigger;
    ScheduledExecutorService toTrigger = null;
    synchronized (stateLock) {
      buffer.add(entry);
      trigger = (forceFlush || (maxBatchSize > 0 && buffer.size() >= maxBatchSize)) && !loopStopped;
      if (!closed) {
        ensureStartedLocked();
        toTrigger = scheduler;
      }
    }
    if (trigger && toTrigger != null) {
      try {
        toTrigger.execute(this::backgroundFlush);
      } catch (RejectedExecutionException e) {
        // The executor stopped between reading it and submitting (close(), or a user executor
        // shut down by its owner). The item stays buffered for flush()/close() to drain.
      }
    }
  }

  private void ensureStartedLocked() {
    if (started || closed) {
      return;
    }
    started = true;
    if (userExecutor != null) {
      scheduler = userExecutor;
    } else {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "temporal-workflow-stream-publisher");
                t.setDaemon(true);
                return t;
              });
    }
    flushTask =
        scheduler.scheduleWithFixedDelay(
            this::backgroundFlush, batchIntervalMs, batchIntervalMs, TimeUnit.MILLISECONDS);
  }

  private void backgroundFlush() {
    synchronized (stateLock) {
      if (loopStopped) {
        // A timed-out flush stopped the loop; a task queued before that must not send.
        return;
      }
    }
    try {
      doFlush();
    } catch (FlushTimeoutException e) {
      // The pending batch was dropped and can't be recovered. Stash the error so
      // flush/close surface it and stop the loop for good: with a user-supplied executor
      // cancelling the periodic task is not enough, since publish() can still submit a
      // triggered flush onto the still-live executor.
      ScheduledFuture<?> toCancel;
      ScheduledExecutorService toStop;
      synchronized (stateLock) {
        deferredError = e;
        loopStopped = true;
        toCancel = flushTask;
        toStop = ownedSchedulerLocked();
      }
      if (toCancel != null) {
        toCancel.cancel(false);
      }
      if (toStop != null) {
        toStop.shutdown();
      }
    } catch (RuntimeException e) {
      // Transient failure: pending stays set for retry on the next tick.
    }
  }

  /**
   * Sends the pending batch (retry) or the buffered batch (new batch). Serialized so concurrent
   * callers send sequentially.
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
          batch = buffer;
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

  private PublishEntry encode(String topic, Object value) {
    Payload payload;
    if (value instanceof Payload) {
      payload = (Payload) value;
    } else {
      payload =
          dataConverter
              .toPayload(value)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "workflowstreams: no payload converter accepted the published value"));
    }
    return new PublishEntry(topic, PayloadWire.encode(payload));
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
   * FlushTimeoutException} from a prior background failure. A user-supplied executor is never shut
   * down; only the periodic flush task is cancelled, leaving the executor free for its other work.
   */
  public void close() {
    ScheduledFuture<?> toCancel;
    ScheduledExecutorService toStop;
    synchronized (stateLock) {
      if (closed) {
        return;
      }
      closed = true;
      toCancel = flushTask;
      toStop = ownedSchedulerLocked();
    }

    if (toCancel != null) {
      toCancel.cancel(false);
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

  /** Returns the executor to shut down on stop, or null when a user executor must be left alone. */
  private ScheduledExecutorService ownedSchedulerLocked() {
    return userExecutor == null ? scheduler : null;
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
