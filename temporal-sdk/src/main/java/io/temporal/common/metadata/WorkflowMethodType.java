package io.temporal.common.metadata;

public enum WorkflowMethodType {
  NONE,
  WORKFLOW,
  SIGNAL,
  QUERY,
  UPDATE,
  UPDATE_VALIDATOR
}
