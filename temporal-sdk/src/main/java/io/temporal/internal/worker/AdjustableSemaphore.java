package io.temporal.internal.worker;

import java.util.concurrent.Semaphore;
import javax.annotation.concurrent.ThreadSafe;

/** A simple implementation of an adjustable semaphore. */
@ThreadSafe
public final class AdjustableSemaphore {
  private final ResizeableSemaphore semaphore;

  /**
   * how many permits are allowed as governed by this semaphore. Access must be synchronized on this
   * object.
   */
  private int maxPermits = 0;

  /** New instances should be configured with setMaxPermits(). */
  public AdjustableSemaphore(int initialPermits) {
    if (initialPermits < 1) {
      throw new IllegalArgumentException(
          "Semaphore size must be at least 1," + " was " + initialPermits);
    }
    this.maxPermits = initialPermits;
    this.semaphore = new ResizeableSemaphore(initialPermits);
  }

  /**
   * Set the max number of permits. Must be greater than zero.
   *
   * <p>Note that if there are more than the new max number of permits currently outstanding, any
   * currently blocking threads or any new threads that start to block after the call will wait
   * until enough permits have been released to have the number of outstanding permits fall below
   * the new maximum. In other words, it does what you probably think it should.
   *
   * @param newMax the new maximum number of permits
   */
  synchronized void setMaxPermits(int newMax) {
    if (newMax < 1) {
      throw new IllegalArgumentException("Semaphore size must be at least 1," + " was " + newMax);
    }

    int delta = newMax - this.maxPermits;

    if (delta == 0) {
      return;
    } else if (delta > 0) {
      // new max is higher, so release that many permits
      this.semaphore.release(delta);
    } else {
      // delta < 0.
      // reducePermits needs a positive #, though.
      this.semaphore.reducePermits(delta * -1);
    }

    this.maxPermits = newMax;
  }

  /** Release a permit back to the semaphore. */
  void release() {
    this.semaphore.release();
  }

  /**
   * Get a permit, blocking if necessary.
   *
   * @throws InterruptedException if interrupted while waiting for a permit
   */
  void acquire() throws InterruptedException {
    this.semaphore.acquire();
  }

  /**
   * A trivial subclass of <code>Semaphore</code> that exposes the reducePermits call to the parent
   * class.
   */
  private static final class ResizeableSemaphore extends Semaphore {
    /** */
    private static final long serialVersionUID = 1L;

    /** Create a new semaphore with 0 permits. */
    ResizeableSemaphore(int initialPermits) {
      super(initialPermits);
    }

    @Override
    protected void reducePermits(int reduction) {
      super.reducePermits(reduction);
    }
  }
}
