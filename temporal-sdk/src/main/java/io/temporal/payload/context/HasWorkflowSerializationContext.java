package io.temporal.payload.context;

import io.temporal.common.converter.DataConverter;

/**
 * {@link SerializationContext} that contains Namespace and Workflow ID of the Serialization Target.
 *
 * <p>This interface is a convenience interface for users that customize {@link DataConverter}s.
 * This interface provides a unified API for {@link SerializationContext} implementations that
 * contain information about the Workflow Execution the Serialization Target belongs to. If some
 * Workflow is Serialization target itself, methods of this interface will return information of
 * that workflow.
 */
public interface HasWorkflowSerializationContext extends SerializationContext {
  /**
   * @return namespace the workflow execution belongs to
   */
  String getNamespace();

  /**
   * @return workflowId of the Workflow Execution the Serialization Target belongs to. If the Target
   *     is a Workflow itself, this method will return the Target's Workflow ID (not the ID of the
   *     parent workflow).
   *     <p>WARNING: When used in the context of a schedule workflow the workflowId may differ on
   *     serialization and deserialization.
   */
  String getWorkflowId();
}
