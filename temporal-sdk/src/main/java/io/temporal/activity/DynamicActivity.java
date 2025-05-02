package io.temporal.activity;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicActivity to implement any number of activity types dynamically. When an activity
 * implementation that extends DynamicActivity is registered it is called for any activity type
 * invocation that doesn't have an explicitly registered handler. Only one instance that implements
 * DynamicActivity per {@link io.temporal.worker.Worker} is allowed.
 *
 * <p>The DynamicActivity can be useful for integrations with existing libraries. For example, it
 * can be used to call some external HTTP API with each function exposed as a different activity
 * type.
 *
 * <p>Use {@link Activity#getExecutionContext()} to query information about the activity type that
 * should be implemented dynamically.
 *
 * @see io.temporal.workflow.DynamicWorkflow
 */
public interface DynamicActivity {
  Object execute(EncodedValues args);
}
