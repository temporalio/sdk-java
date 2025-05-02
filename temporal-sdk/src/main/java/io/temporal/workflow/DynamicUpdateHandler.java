package io.temporal.workflow;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicUpdateHandler to process any update dynamically. This is useful for a library level
 * code and implementation of DSLs.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register an implementation of the
 * DynamicUpdateListener. Only one such listener can be registered per workflow execution.
 *
 * <p>When registered any signals which don't have a specific handler will be delivered to it.
 *
 * @see DynamicQueryHandler
 * @see DynamicSignalHandler
 * @see DynamicWorkflow
 */
public interface DynamicUpdateHandler {

  default void handleValidate(String updateName, EncodedValues args) {}

  Object handleExecute(String updateName, EncodedValues args);

  /** Short description of the Update handler. */
  default String getDescription() {
    return "Dynamic update handler";
  }

  /** Returns the actions taken if a workflow exits with a running instance of this handler. */
  default HandlerUnfinishedPolicy getUnfinishedPolicy(String updateName) {
    return HandlerUnfinishedPolicy.WARN_AND_ABANDON;
  }
}
