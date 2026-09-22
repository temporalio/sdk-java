package io.temporal.workflowstreams;

import io.temporal.common.Experimental;
import io.temporal.workflowstreams.internal.SubscriptionDriver;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A blocking, single-use subscription over a workflow stream. Polling runs on the owning client's
 * poll executor (shared with the client's other subscriptions); the consuming thread only blocks
 * waiting for the next item. The subscription ends cleanly ({@code hasNext() == false}) when the
 * workflow reaches a terminal state, and automatically follows continue-as-new chains; closing the
 * owning {@link WorkflowStreamClient} also ends it.
 *
 * <p>{@link #close} stops the subscription before the next poll; a poll already blocked on the
 * server is not interrupted.
 */
@Experimental
public final class WorkflowStreamSubscription
    implements Iterator<WorkflowStreamItem>, Iterable<WorkflowStreamItem>, AutoCloseable {
  private final SubscriptionDriver driver;

  private final Object lock = new Object();

  // Hand-off state, guarded by lock. The driver's pending-stage backpressure keeps the buffer
  // at no more than one item: each onNext parks the driver on a gate that next() releases when
  // the consumer takes the item, so the next long poll only fires once the consumer drains what
  // the driver already fetched — the same pacing as driving the poll loop on the consumer thread.
  private final Deque<WorkflowStreamItem> buffer = new ArrayDeque<>();
  private CompletableFuture<Void> pendingGate;
  private Throwable error;
  private boolean streamDone;

  // Consumer-thread state.
  private boolean started;
  private boolean errorThrown;

  WorkflowStreamSubscription(Function<WorkflowStreamListener, SubscriptionDriver> driverFactory) {
    this.driver = driverFactory.apply(new AdapterListener());
    // One hook covers every way the stream ends: terminal state, failure, close(), and the
    // owning client closing. It wakes a consumer blocked in hasNext().
    driver
        .getDoneFuture()
        .whenComplete(
            (ignored, failure) -> {
              synchronized (lock) {
                if (failure != null) {
                  error = unwrap(failure);
                }
                streamDone = true;
                lock.notifyAll();
              }
            });
  }

  /**
   * Returns this subscription; it is single-use, so iterate it at most once (typically with a
   * for-each loop).
   */
  @Override
  public Iterator<WorkflowStreamItem> iterator() {
    return this;
  }

  /**
   * Returns whether another item is available, blocking until one is (or the stream ends). The
   * first call starts the polling; an unrecoverable poll failure is rethrown here (once — the
   * subscription is over afterwards).
   */
  @Override
  public boolean hasNext() {
    if (!started) {
      started = true;
      driver.start();
    }
    synchronized (lock) {
      while (buffer.isEmpty() && !streamDone) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          driver.close();
          return !buffer.isEmpty();
        }
      }
      if (!buffer.isEmpty()) {
        return true;
      }
      if (error != null && !errorThrown) {
        errorThrown = true;
        if (error instanceof RuntimeException) {
          throw (RuntimeException) error;
        }
        throw new RuntimeException(error);
      }
      return false;
    }
  }

  @Override
  public WorkflowStreamItem next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    WorkflowStreamItem item;
    CompletableFuture<Void> gate = null;
    synchronized (lock) {
      item = buffer.poll();
      if (buffer.isEmpty() && pendingGate != null) {
        gate = pendingGate;
        pendingGate = null;
      }
    }
    // Completed outside the lock: it resumes the driver (via an executor hop), which may call
    // onNext and take the lock again.
    if (gate != null) {
      gate.complete(null);
    }
    return item;
  }

  /** Stops the subscription before the next poll. Items already fetched still drain. */
  @Override
  public void close() {
    CompletableFuture<Void> gate;
    synchronized (lock) {
      gate = pendingGate;
      pendingGate = null;
    }
    driver.close();
    if (gate != null) {
      gate.complete(null);
    }
  }

  private class AdapterListener implements WorkflowStreamListener {
    @Override
    public CompletionStage<Void> onNext(WorkflowStreamItem item) {
      CompletableFuture<Void> gate = new CompletableFuture<>();
      synchronized (lock) {
        buffer.add(item);
        pendingGate = gate;
        lock.notifyAll();
      }
      return gate;
    }

    // The done-future hook records the failure for hasNext() to rethrow; the default warn
    // log would just duplicate it.
    @Override
    public void onError(Throwable failure) {}

    @Override
    public void onCompleted() {}
  }

  private static Throwable unwrap(Throwable e) {
    while (e instanceof CompletionException && e.getCause() != null) {
      e = e.getCause();
    }
    return e;
  }
}
