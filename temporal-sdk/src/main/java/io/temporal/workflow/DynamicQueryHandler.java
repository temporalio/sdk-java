package io.temporal.workflow;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicQueryHandler to process any query dynamically. This is useful for library-level code
 * and implementation of DSLs.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register an implementation of the
 * DynamicQueryListener. Only one such listener can be registered per workflow execution.
 *
 * <p>When registered any queries which don't have a specific handler will be delivered to it.
 *
 * @see DynamicSignalHandler
 * @see DynamicWorkflow
 * @see DynamicUpdateHandler
 */
public interface DynamicQueryHandler {
  Object handle(String queryType, EncodedValues args);

  /** Short description of the Query handler. */
  default String getDescription() {
    return "Dynamic query handler";
  }
}
