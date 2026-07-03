package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.ArrayList;
import java.util.List;

/**
 * The <em>write</em> side of cancellation. Whoever holds the {@code CancelSource} can request
 * cancellation while everyone else observes using {@link #token()}.
 */
public final class CancelSource {

  private final Object lock = new Object();
  private volatile boolean cancelled = false;

  /** Pending callbacks. Set to {@code null} by {@link #cancel()} once it takes ownership. */
  private List<Runnable> callbacks = new ArrayList<>();

  private final CancellationToken token =
      new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
          return cancelled;
        }

        @Override
        public void throwIfCancellationRequested() {
          if (cancelled) throw new java.util.concurrent.CancellationException();
        }

        @Override
        public Registration onCancel(Runnable cb) {
          synchronized (lock) {
            if (!cancelled) {
              callbacks.add(cb);
              return () -> {
                synchronized (lock) {
                  if (callbacks != null) {
                    callbacks.remove(cb);
                  }
                }
              };
            }
          }
          runSafely(cb);
          return () -> {};
        }
      };

  /** The read-only token to hand to code that observes cancellation. */
  public CancellationToken token() {
    return token;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  /** Requests cancellation. Idempotent; fires each registered callback exactly once. */
  public void cancel() {
    List<Runnable> toRun;
    synchronized (lock) {
      if (cancelled) {
        return;
      }
      cancelled = true;
      toRun = callbacks;
      callbacks = null;
    }
    for (Runnable cb : toRun) {
      runSafely(cb);
    }
  }

  private static void runSafely(Runnable cb) {
    try {
      cb.run();
    } catch (Throwable ignored) {
      /* a bad callback must not block others */
    }
  }

  /** Creates a source whose token is cancelled whenever any {@code parent} token is cancelled. */
  public static CancelSource linkedTo(CancellationToken... parents) {
    CancelSource s = new CancelSource();
    for (CancellationToken p : parents) p.onCancel(s::cancel);
    return s;
  }
}
