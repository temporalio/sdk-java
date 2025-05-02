package io.temporal.internal.replay;

import io.grpc.Deadline;
import io.temporal.api.history.v1.HistoryEvent;
import java.util.Iterator;

public interface WorkflowHistoryIterator extends Iterator<HistoryEvent> {
  void initDeadline(Deadline deadline);
}
