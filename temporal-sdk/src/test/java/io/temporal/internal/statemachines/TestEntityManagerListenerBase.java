package io.temporal.internal.statemachines;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.UpdateMessage;
import io.temporal.workflow.Functions;
import java.util.ArrayDeque;
import java.util.Queue;

abstract class TestEntityManagerListenerBase implements StatesMachinesCallback {

  private final Queue<Functions.Proc> callbacks = new ArrayDeque<>();

  @Override
  public final void start(HistoryEvent startWorkflowEvent) {
    buildWorkflow(AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected abstract void buildWorkflow(AsyncWorkflowBuilder<Void> builder);

  @Override
  public final void signal(HistoryEvent signalEvent) {
    signal(signalEvent, AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {}

  @Override
  public void update(UpdateMessage message) {
    update(message, AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {}

  @Override
  public void cancel(HistoryEvent cancelEvent) {}

  @Override
  public final void eventLoop() {
    while (true) {
      Functions.Proc callback = callbacks.poll();
      if (callback == null) {
        break;
      }
      callback.apply();
    }
  }
}
