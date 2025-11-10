package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.sync.StubMarker;
import javax.annotation.Nullable;

/**
 * Supports signalling and cancelling any workflow by the workflow type and their id. This is useful
 * when an external workflow type is not known at compile time and to call workflows in other
 * languages.
 *
 * @see Workflow#newUntypedExternalWorkflowStub(String)
 */
public interface ExternalWorkflowStub {

  /**
   * Extracts untyped ExternalWorkflowStub from a typed workflow stub created through {@link
   * Workflow#newExternalWorkflowStub(Class, String)}.
   *
   * @param typed typed external workflow stub
   * @param <T> type of the workflow stub interface
   * @return untyped external workflow stub for the same workflow instance.
   */
  static <T> ExternalWorkflowStub fromTyped(T typed) {
    if (!(typed instanceof StubMarker)) {
      throw new IllegalArgumentException(
          "arguments must be created through Workflow.newChildWorkflowStub");
    }
    if (typed instanceof ChildWorkflowStub) {
      throw new IllegalArgumentException(
          "Use ChildWorkflowStub.fromTyped to extract stub created through Workflow#newChildWorkflowStub");
    }
    @SuppressWarnings("unchecked")
    StubMarker supplier = (StubMarker) typed;
    return (ExternalWorkflowStub) supplier.__getUntypedStub();
  }

  /**
   * @return workflow execution used to create this stub.
   */
  WorkflowExecution getExecution();

  /**
   * Synchronously signals a workflow by invoking its signal handler. Usually a signal handler is a
   * method annotated with {@link io.temporal.workflow.SignalMethod}.
   *
   * @param signalName name of the signal handler. Usually it is a method name.
   * @param args signal method arguments
   * @throws SignalExternalWorkflowException if there is failure to signal the workflow.
   */
  void signal(String signalName, Object... args);

  /** Request cancellation of the workflow execution. */
  void cancel();

  /**
   * Request cancellation of the workflow execution with a reason.
   *
   * @param reason optional reason for cancellation.
   */
  void cancel(@Nullable String reason);
}
