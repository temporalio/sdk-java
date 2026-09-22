package io.temporal.internal.concurrent.structured;

import io.temporal.common.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The <em>write</em> side of cancellation. Whoever holds the {@code CancelSource} can request
 * cancellation while everyone else observes using {@link #token()}.
 *
 * <p>Sources can be <em>linked</em>: a source created via {@link #linkedTo} is cancelled
 * automatically when any of its parent tokens is cancelled, without giving the child the power to
 * cancel the parent.
 *
 * @param <E> the exception surfaced by the token when cancellation is requested
 */
public final class CancelSource<E extends RuntimeException> {

  private final Object lock = new Object();
  private final Supplier<E> defaultException;
  private volatile boolean cancelled = false;
  private volatile E cancellationException;

  /** Pending callbacks. Set to {@code null} by {@link #cancel} once it takes ownership. */
  private List<Runnable> callbacks = new ArrayList<>();

  public CancelSource(Supplier<E> defaultException) {
    this.defaultException = defaultException;
  }

  private final CancellationToken<E> token =
      new CancellationToken<E>() {
        @Override
        public boolean isCancellationRequested() {
          return cancelled;
        }

        @Override
        public void throwIfCancellationRequested() {
          if (cancelled) {
            throw cancellationException;
          }
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
  public CancellationToken<E> token() {
    return token;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  /** Requests cancellation with a freshly created exception. */
  public void cancel() {
    cancel(defaultException.get());
  }

  /** Requests cancellation, surfacing {@code exception} from the token. Idempotent. */
  public void cancel(E exception) {
    List<Runnable> toRun;
    synchronized (lock) {
      if (cancelled) {
        return;
      }
      cancellationException = exception;
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

  /**
   * Creates a source whose token is cancelled (via {@link #cancel()}) whenever any {@code parent}
   * token is cancelled.
   */
  @SafeVarargs
  public static <E extends RuntimeException> CancelSource<E> linkedTo(
      Supplier<E> defaultException, CancellationToken<? extends E>... parents) {
    CancelSource<E> s = new CancelSource<>(defaultException);
    for (CancellationToken<? extends E> p : parents) {
      p.onCancel(s::cancel);
    }
    return s;
  }
}
