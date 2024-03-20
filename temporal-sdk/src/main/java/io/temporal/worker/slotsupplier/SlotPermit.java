package io.temporal.worker.slotsupplier;

import java.util.concurrent.atomic.AtomicLong;

public class SlotPermit {
  public final long id;
  public final Object userData;

  private static final AtomicLong nextId = new AtomicLong(0);

  public SlotPermit() {
    id = nextId.getAndIncrement();
    this.userData = null;
  }

  public SlotPermit(Object userData) {
    id = nextId.getAndIncrement();
    this.userData = userData;
  }
}
