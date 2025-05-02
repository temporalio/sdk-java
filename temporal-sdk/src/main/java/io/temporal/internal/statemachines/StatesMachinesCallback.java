package io.temporal.internal.statemachines;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.UpdateMessage;

public interface StatesMachinesCallback {

  void start(HistoryEvent startWorkflowEvent);

  void signal(HistoryEvent signalEvent);

  void update(UpdateMessage message);

  void cancel(HistoryEvent cancelEvent);

  void eventLoop();
}
