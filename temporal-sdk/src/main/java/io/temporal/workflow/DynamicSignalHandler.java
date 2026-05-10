package io.temporal.workflow;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicSignalHandler to process any signal dynamically. This is useful for library-level code
 * and implementation of DSLs.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register an implementation of the
 * DynamicSignalListener. Only one such listener can be registered per workflow execution.
 *
 * <p>When registered any signals which don't have a specific handler will be delivered to it.
 *
 * @see DynamicQueryHandler
 * @see DynamicWorkflow
 * @see DynamicUpdateHandler
 */
public interface DynamicSignalHandler {
  void handle(String signalName, EncodedValues args);

  /** Returns the actions taken if a workflow exits with a running instance of this handler. */
  default HandlerUnfinishedPolicy getUnfinishedPolicy(String signalName) {
    return HandlerUnfinishedPolicy.WARN_AND_ABANDON;
  }

  /** Short description of the Update handler. */
  default String getDescription() {
    return "Dynamic signal handler";
  }
}
