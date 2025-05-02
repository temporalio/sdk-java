package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.sync.StubMarker;

/**
 * Supports signalling and cancelling any workflows by the workflow type and their id. This is
 * useful when an external workflow type is not known at the compile time and to call workflows in
 * other languages.
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
          "Use ChildWorkflowStub.fromTyped to extract sbub created through Workflow#newChildWorkflowStub");
    }
    @SuppressWarnings("unchecked")
    StubMarker supplier = (StubMarker) typed;
    return (ExternalWorkflowStub) supplier.__getUntypedStub();
  }

  WorkflowExecution getExecution();

  void signal(String signalName, Object... args);

  void cancel();
}
