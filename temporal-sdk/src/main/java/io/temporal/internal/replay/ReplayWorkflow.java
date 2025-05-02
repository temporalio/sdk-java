package io.temporal.internal.replay;

import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.internal.statemachines.UpdateProtocolCallback;
import java.util.Optional;

/**
 * Manages event loop, workflow method, an abstraction level over Deterministic Runner to provide a
 * communication interface for the control thread for Start, Signal, Query, etc.
 */
public interface ReplayWorkflow {

  void start(HistoryEvent event, ReplayWorkflowContext context);

  /** Handle an external signal event. */
  void handleSignal(String signalName, Optional<Payloads> input, long eventId, Header header);

  /** Handle an update workflow execution event */
  void handleUpdate(
      String updateName,
      String updateId,
      Optional<Payloads> input,
      long eventId,
      Header header,
      UpdateProtocolCallback callbacks);

  /**
   * @return true if the execution of the workflow method is finished or an exit was explicitly
   *     called by it
   */
  boolean eventLoop();

  /**
   * @return null means no output yet
   */
  Optional<Payloads> getOutput();

  void cancel(String reason);

  void close();

  /**
   * Called after all history is replayed and workflow cannot make any progress if workflow task is
   * a query.
   *
   * @param query arguments
   * @return query result
   */
  Optional<Payloads> query(WorkflowQuery query);

  // TODO we should inverse the control. WorkflowContext should have and expose a reference to
  //  ReplayWorkflow, not the other way around.
  /**
   * @return the fullest context of the workflow possible
   */
  WorkflowContext getWorkflowContext();
}
