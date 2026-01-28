package io.temporal.internal.replay;

import io.grpc.Deadline;
import io.temporal.api.history.v1.HistoryEvent;
import java.util.Iterator;

class FullHistoryIterator implements WorkflowHistoryIterator {
  private final Iterator<HistoryEvent> iterator;

  FullHistoryIterator(Iterable<HistoryEvent> iterable) {
    this.iterator = iterable.iterator();
  }

  @Override
  public void initDeadline(Deadline deadline) {}

  @Override
  public boolean hasNext() {
    return iterator.hasNext();
  }

  @Override
  public HistoryEvent next() {
    return iterator.next();
  }
}
