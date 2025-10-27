package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.sync.StubMarker;
import java.lang.reflect.Type;

/**
 * Supports starting and signalling child workflows by the name and list of arguments. This is
 * useful when a child workflow type is not known at the compile time and to call child workflows in
 * other languages.
 *
 * @see Workflow#newChildWorkflowStub(Class)
 */
public interface ChildWorkflowStub {

  /**
   * Extracts untyped WorkflowStub from a typed workflow stub created through {@link
   * Workflow#newChildWorkflowStub(Class)}.
   *
   * @param typed typed workflow stub
   * @param <T> type of the workflow stub interface
   * @return untyped workflow stub for the same workflow instance.
   */
  static <T> ChildWorkflowStub fromTyped(T typed) {
    if (!(typed instanceof StubMarker)) {
      throw new IllegalArgumentException(
          "arguments must be created through Workflow.newChildWorkflowStub");
    }
    if (typed instanceof ExternalWorkflowStub) {
      throw new IllegalArgumentException(
          "Use ExternalWorkflowStub.fromTyped to extract stub created through Workflow#newExternalWorkflowStub");
    }
    @SuppressWarnings("unchecked")
    StubMarker supplier = (StubMarker) typed;
    return (ChildWorkflowStub) supplier.__getUntypedStub();
  }

  /**
   * @return workflow type name.
   */
  String getWorkflowType();

  /**
   * If workflow completes before this promise is ready then the child might not start at all.
   *
   * @return promise that becomes ready once the child has started.
   */
  Promise<WorkflowExecution> getExecution();

  /**
   * @return child workflow options used to create this stub.
   */
  ChildWorkflowOptions getOptions();

  /**
   * Synchronously starts a child workflow execution and waits for its result.
   *
   * @param resultClass the expected return class of the workflow.
   * @param args workflow arguments.
   * @param <R> return type.
   * @return workflow result.
   */
  <R> R execute(Class<R> resultClass, Object... args);

  /**
   * Synchronously starts a child workflow execution and waits for its result.
   *
   * @param resultClass the expected return class of the workflow.
   * @param resultType the expected return class of the workflow. Differs from resultClass for
   *     generic types.
   * @param args workflow arguments.
   * @param <R> return type.
   * @return workflow result.
   */
  <R> R execute(Class<R> resultClass, Type resultType, Object... args);

  /**
   * Executes a child workflow asynchronously.
   *
   * @param resultClass the expected return type of the workflow.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(Class<R> resultClass, Object... args);

  /**
   * Executes a child workflow asynchronously.
   *
   * @param resultClass the expected return type of the workflow.
   * @param resultType the expected return class of the workflow. Differs from resultClass for
   *     generic types.
   * @param args arguments of the activity.
   * @param <R> return type.
   * @return Promise to the activity result.
   */
  <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object... args);

  /**
   * Synchronously signals a workflow by invoking its signal handler. Usually a signal handler is a
   * method annotated with {@link io.temporal.workflow.SignalMethod}.
   *
   * @param signalName name of the signal handler. Usually it is a method name.
   * @param args signal method arguments.
   * @throws SignalExternalWorkflowException if there is failure to signal the workflow.
   */
  void signal(String signalName, Object... args);
}
